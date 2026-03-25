package com.hmdp.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.hmdp.config.RabbitMQConfig.CACHE_INVALIDATION_DEAD_QUEUE;

@Slf4j
@Component
public class CacheInvalidationDeadLetterListener {

    @RabbitListener(queues = CACHE_INVALIDATION_DEAD_QUEUE)
    public void handleDeadLetter(String eventId) {
        log.error("缓存失效事件进入死信队列，请人工介入处理，eventId={}", eventId);
    }
}
