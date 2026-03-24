package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.config.RabbitMQConfig.VOUCHER_ORDER_QUEUE;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_DEAD_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_IDX_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final long PENDING_TTL_MINUTES = 30L;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT ;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        if (!isLock){
            // 获取锁失败时抛异常，交给 MQ 重试或补偿任务处理
            throw new IllegalStateException("获取用户订单锁失败，稍后重试");
        }


        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();

        if (r!=0){
            //2.1不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足~":"一人一单呢亲~");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 先登记待补偿订单，再发消息，避免发送失败时无迹可查
        long now = System.currentTimeMillis();
        recordPendingOrder(voucherOrder, now, 0, now + TimeUnit.SECONDS.toMillis(30));
        try {
            rabbitTemplate.convertAndSend(VOUCHER_ORDER_QUEUE, voucherOrder);
        } catch (Exception e) {
            log.error("发送秒杀订单消息失败，已记录待补偿订单，orderId={}", orderId, e);
        }

        //3.返回订单id
        return Result.ok(orderId);

    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = voucherOrder.getUserId();

        //6.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //6.2判断是否存在
        if (count > 0){

            log.error("每个用户只能购买一次！");
            return;
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)
                .update();
        if (!success){
            //扣减失败
            log.error("库存不足！");
            return;
        }

        //7.创建订单
        save(voucherOrder);


    }

    public void clearPendingOrder(Long orderId) {
        String orderIdStr = String.valueOf(orderId);
        stringRedisTemplate.delete(SECKILL_PENDING_ORDER_KEY + orderIdStr);
        stringRedisTemplate.opsForZSet().remove(SECKILL_PENDING_ORDER_IDX_KEY, orderIdStr);
    }

    public void compensatePendingOrders(int batchSize, int maxRetry, long baseDelaySeconds) {
        long now = System.currentTimeMillis();
        Set<String> dueOrderIds = stringRedisTemplate.opsForZSet().rangeByScore(
                SECKILL_PENDING_ORDER_IDX_KEY,
                0,
                now,
                0,
                batchSize
        );
        if (dueOrderIds == null || dueOrderIds.isEmpty()) {
            return;
        }

        for (String orderIdStr : dueOrderIds) {
            String pendingKey = SECKILL_PENDING_ORDER_KEY + orderIdStr;
            Map<Object, Object> pendingData = stringRedisTemplate.opsForHash().entries(pendingKey);
            if (pendingData == null || pendingData.isEmpty()) {
                stringRedisTemplate.opsForZSet().remove(SECKILL_PENDING_ORDER_IDX_KEY, orderIdStr);
                continue;
            }

            Long orderId = parseLong(pendingData.get("orderId"));
            Long userId = parseLong(pendingData.get("userId"));
            Long voucherId = parseLong(pendingData.get("voucherId"));
            Integer retryCount = parseInt(pendingData.get("retryCount"));

            if (orderId == null || userId == null || voucherId == null || retryCount == null) {
                log.error("待补偿订单数据非法，移入死信集合，orderId={}", orderIdStr);
                moveToDead(orderIdStr);
                continue;
            }

                boolean exists = lambdaQuery()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .count() > 0;
            if (exists) {
                clearPendingOrder(orderId);
                continue;
            }

            if (retryCount >= maxRetry) {
                log.error("待补偿订单重试次数超限，移入死信，orderId={}, retryCount={}", orderId, retryCount);
                moveToDead(orderIdStr);
                continue;
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);

            try {
                rabbitTemplate.convertAndSend(VOUCHER_ORDER_QUEUE, voucherOrder);
                long nextRetryTime = now + TimeUnit.SECONDS.toMillis(baseDelaySeconds * (1L << retryCount));
                recordPendingOrder(voucherOrder, now, retryCount + 1, nextRetryTime);
            } catch (Exception e) {
                log.error("补偿重发消息失败，orderId={}", orderId, e);
                long nextRetryTime = now + TimeUnit.SECONDS.toMillis(baseDelaySeconds * (1L << retryCount));
                recordPendingOrder(voucherOrder, now, retryCount + 1, nextRetryTime);
            }
        }
    }

    private void recordPendingOrder(VoucherOrder voucherOrder, long now, int retryCount, long nextRetryTime) {
        String orderIdStr = String.valueOf(voucherOrder.getId());
        String pendingKey = SECKILL_PENDING_ORDER_KEY + orderIdStr;
        Map<String, String> data = new HashMap<>(8);
        data.put("orderId", orderIdStr);
        data.put("userId", String.valueOf(voucherOrder.getUserId()));
        data.put("voucherId", String.valueOf(voucherOrder.getVoucherId()));
        data.put("retryCount", String.valueOf(retryCount));
        data.put("createdAt", String.valueOf(now));
        data.put("nextRetryTime", String.valueOf(nextRetryTime));

        stringRedisTemplate.opsForHash().putAll(pendingKey, data);
        stringRedisTemplate.expire(pendingKey, PENDING_TTL_MINUTES, TimeUnit.MINUTES);
        stringRedisTemplate.opsForZSet().add(SECKILL_PENDING_ORDER_IDX_KEY, orderIdStr, nextRetryTime);
    }

    private void moveToDead(String orderIdStr) {
        clearPendingOrder(Long.valueOf(orderIdStr));
        stringRedisTemplate.opsForSet().add(SECKILL_PENDING_ORDER_DEAD_KEY, orderIdStr);
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

}
