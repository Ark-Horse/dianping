package com.hmdp.task;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class VoucherOrderCompensationTask {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Value("${hmdp.seckill.compensation.batch-size:100}")
    private int batchSize;

    @Value("${hmdp.seckill.compensation.max-retry:8}")
    private int maxRetry;

    @Value("${hmdp.seckill.compensation.base-delay-seconds:30}")
    private long baseDelaySeconds;

    @Scheduled(cron = "${hmdp.seckill.compensation.cron:*/30 * * * * ?}")
    public void compensatePendingOrders() {
        try {
            voucherOrderService.compensatePendingOrders(batchSize, maxRetry, baseDelaySeconds);
        } catch (Exception e) {
            log.error("执行秒杀订单补偿任务异常", e);
        }
    }
}
