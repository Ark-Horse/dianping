package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String VOUCHER_ORDER_QUEUE = "voucher.order.queue";

    public static final String CACHE_INVALIDATION_EXCHANGE = "cache.invalidation.exchange";
    public static final String CACHE_INVALIDATION_QUEUE = "cache.invalidation.queue";
    public static final String CACHE_INVALIDATION_RETRY_QUEUE = "cache.invalidation.retry.queue";
    public static final String CACHE_INVALIDATION_DEAD_QUEUE = "cache.invalidation.dead.queue";

    public static final String CACHE_INVALIDATION_ROUTING_KEY = "cache.invalidation";
    public static final String CACHE_INVALIDATION_RETRY_ROUTING_KEY = "cache.invalidation.retry";
    public static final String CACHE_INVALIDATION_DEAD_ROUTING_KEY = "cache.invalidation.dead";

    @Bean
    public Queue voucherOrderQueue() {
        return new Queue(VOUCHER_ORDER_QUEUE, true);
    }

    @Bean
    public DirectExchange cacheInvalidationExchange() {
        return new DirectExchange(CACHE_INVALIDATION_EXCHANGE, true, false);
    }

    @Bean
    public Queue cacheInvalidationQueue() {
        return QueueBuilder.durable(CACHE_INVALIDATION_QUEUE).build();
    }

    @Bean
    public Queue cacheInvalidationRetryQueue() {
        return QueueBuilder.durable(CACHE_INVALIDATION_RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", CACHE_INVALIDATION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CACHE_INVALIDATION_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue cacheInvalidationDeadQueue() {
        return QueueBuilder.durable(CACHE_INVALIDATION_DEAD_QUEUE).build();
    }

    @Bean
    public Binding cacheInvalidationBinding() {
        return BindingBuilder.bind(cacheInvalidationQueue())
                .to(cacheInvalidationExchange())
                .with(CACHE_INVALIDATION_ROUTING_KEY);
    }

    @Bean
    public Binding cacheInvalidationRetryBinding() {
        return BindingBuilder.bind(cacheInvalidationRetryQueue())
                .to(cacheInvalidationExchange())
                .with(CACHE_INVALIDATION_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding cacheInvalidationDeadBinding() {
        return BindingBuilder.bind(cacheInvalidationDeadQueue())
                .to(cacheInvalidationExchange())
                .with(CACHE_INVALIDATION_DEAD_ROUTING_KEY);
    }
}
