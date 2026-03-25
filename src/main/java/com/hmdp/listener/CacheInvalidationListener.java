package com.hmdp.listener;

import com.hmdp.dto.CacheInvalidationEvent;
import com.hmdp.service.impl.CacheInvalidationServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.config.RabbitMQConfig.CACHE_INVALIDATION_QUEUE;

@Slf4j
@Component
public class CacheInvalidationListener {

    @Resource
    private CacheInvalidationServiceImpl cacheInvalidationService;

    @RabbitListener(queues = CACHE_INVALIDATION_QUEUE)
    public void handleCacheInvalidation(CacheInvalidationEvent event, Message message) {
        try {
            Integer retryCount = cacheInvalidationService.resolveRetryCount(message);
            cacheInvalidationService.handleInvalidationEvent(event, retryCount);
        } catch (Exception e) {
            log.error("处理缓存失效消息异常", e);
        }
    }
}
