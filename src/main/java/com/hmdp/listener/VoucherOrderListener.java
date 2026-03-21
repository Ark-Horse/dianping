package com.hmdp.listener;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.config.RabbitMQConfig.VOUCHER_ORDER_QUEUE;

@Slf4j
@Component
public class VoucherOrderListener {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @RabbitListener(queues = VOUCHER_ORDER_QUEUE)
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        try {
            voucherOrderService.handleVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理RabbitMQ订单消息异常", e);
            throw e;
        }
    }
}
