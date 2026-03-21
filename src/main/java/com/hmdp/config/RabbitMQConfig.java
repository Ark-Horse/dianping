package com.hmdp.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String VOUCHER_ORDER_QUEUE = "voucher.order.queue";

    @Bean
    public Queue voucherOrderQueue() {
        return new Queue(VOUCHER_ORDER_QUEUE, true);
    }
}
