package com.hmdp.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.hmdp.dto.CacheInvalidationEvent;
import com.hmdp.service.CacheInvalidationService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.config.RabbitMQConfig.CACHE_INVALIDATION_DEAD_ROUTING_KEY;
import static com.hmdp.config.RabbitMQConfig.CACHE_INVALIDATION_EXCHANGE;
import static com.hmdp.config.RabbitMQConfig.CACHE_INVALIDATION_ROUTING_KEY;
import static com.hmdp.config.RabbitMQConfig.CACHE_INVALIDATION_RETRY_ROUTING_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_INVALIDATION_DEAD_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_INVALIDATION_IDEMPOTENT_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_INVALIDATION_PENDING_IDX_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_INVALIDATION_PENDING_KEY;

@Slf4j
@Service
public class CacheInvalidationServiceImpl implements CacheInvalidationService {

    private static final String RETRY_HEADER = "x-cache-retry-count";
    private static final long PENDING_TTL_MINUTES = 30L;
    private static final long IDEMPOTENT_PROCESSING_LOCK_SECONDS = 30L;
    private static final long IDEMPOTENT_DONE_TTL_HOURS = 24L;

    @Value("${hmdp.cache.invalidation.compensation.max-retry:8}")
    private int maxRetry;

    @Value("${hmdp.cache.invalidation.compensation.base-delay-seconds:30}")
    private long baseDelaySeconds;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public void publishInvalidationEvent(String businessType, String businessId, String cacheKey, String operation) {
        CacheInvalidationEvent event = new CacheInvalidationEvent();
        event.setEventId(String.valueOf(redisIdWorker.nextId("cache_invalidation")));
        event.setBusinessType(businessType);
        event.setBusinessId(businessId);
        event.setCacheKey(cacheKey);
        event.setOperation(operation);
        event.setRetryCount(0);

        long now = System.currentTimeMillis();
        event.setCreatedAt(now);
        event.setNextRetryTime(now);

        recordPendingEvent(event, now, 0, now);
        try {
            sendToMain(event, 0);
        } catch (Exception e) {
            log.error("发布缓存失效事件失败，eventId={}", event.getEventId(), e);
        }
    }

    @Override
    public void handleInvalidationEvent(CacheInvalidationEvent event, Integer messageRetryCount) {
        if (event == null || event.getEventId() == null || event.getCacheKey() == null) {
            log.error("缓存失效事件非法，直接丢弃，event={}", event);
            return;
        }

        String idempotentEventKey = buildIdempotentEventKey(event);
        String processingKey = idempotentEventKey + ":processing";
        String doneKey = idempotentEventKey + ":done";

        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(doneKey))) {
            clearPendingEvent(event.getEventId());
            log.debug("命中幂等已处理标记，跳过重复消费，eventId={}, idemKey={}", event.getEventId(), idempotentEventKey);
            return;
        }

        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                processingKey,
                event.getEventId(),
                IDEMPOTENT_PROCESSING_LOCK_SECONDS,
                TimeUnit.SECONDS
        );
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("幂等处理中锁已存在，跳过并发重复消费，eventId={}, idemKey={}", event.getEventId(), idempotentEventKey);
            return;
        }

        int retryCount = messageRetryCount == null ? 0 : messageRetryCount;
        try {
            stringRedisTemplate.delete(event.getCacheKey());
            cacheClient.invalidateLocalCache(event.getCacheKey());
            clearPendingEvent(event.getEventId());
            stringRedisTemplate.opsForValue().set(doneKey, event.getEventId(), IDEMPOTENT_DONE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            // 实时消费失败时先按统一策略进行重试/死信，不向 broker 抛异常避免无限重回队
            handleRetryOrDead(event, retryCount + 1, maxRetry, baseDelaySeconds, e.getMessage());
        } finally {
            stringRedisTemplate.delete(processingKey);
        }
    }

    @Override
    public void compensatePendingInvalidations(int batchSize, int maxRetry, long baseDelaySeconds) {
        long now = System.currentTimeMillis();
        Set<String> dueEventIds = stringRedisTemplate.opsForZSet().rangeByScore(
                CACHE_INVALIDATION_PENDING_IDX_KEY,
                0,
                now,
                0,
                batchSize
        );
        if (dueEventIds == null || dueEventIds.isEmpty()) {
            return;
        }

        for (String eventId : dueEventIds) {
            String pendingKey = CACHE_INVALIDATION_PENDING_KEY + eventId;
            Map<Object, Object> pendingData = stringRedisTemplate.opsForHash().entries(pendingKey);
            if (pendingData == null || pendingData.isEmpty()) {
                stringRedisTemplate.opsForZSet().remove(CACHE_INVALIDATION_PENDING_IDX_KEY, eventId);
                continue;
            }

            CacheInvalidationEvent event = toEvent(pendingData);
            if (event == null || event.getEventId() == null || event.getCacheKey() == null) {
                log.error("待补偿缓存失效事件数据非法，移入死信，eventId={}", eventId);
                moveToDead(eventId, "invalid_pending_data");
                continue;
            }

            Integer retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
            if (retryCount >= maxRetry) {
                moveToDead(eventId, "exceed_max_retry");
                continue;
            }

            try {
                long delaySeconds = baseDelaySeconds * (1L << retryCount);
                sendToRetry(event, retryCount + 1, delaySeconds, "compensation_retry");
                long nextRetryTime = now + TimeUnit.SECONDS.toMillis(delaySeconds);
                recordPendingEvent(event, now, retryCount + 1, nextRetryTime);
            } catch (Exception e) {
                long delaySeconds = baseDelaySeconds * (1L << retryCount);
                long nextRetryTime = now + TimeUnit.SECONDS.toMillis(delaySeconds);
                recordPendingEvent(event, now, retryCount + 1, nextRetryTime);
                log.error("补偿重发缓存失效事件失败，eventId={}", eventId, e);
            }
        }
    }

    public Integer resolveRetryCount(Message message) {
        if (message == null || message.getMessageProperties() == null) {
            return 0;
        }
        Object value = message.getMessageProperties().getHeaders().get(RETRY_HEADER);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private void handleRetryOrDead(CacheInvalidationEvent event, int retryCount, int maxRetry, long baseDelaySeconds, String reason) {
        if (retryCount > maxRetry) {
            moveToDead(event.getEventId(), reason);
            return;
        }
        long now = System.currentTimeMillis();
        long delaySeconds = baseDelaySeconds * (1L << Math.max(retryCount - 1, 0));
        long nextRetryTime = now + TimeUnit.SECONDS.toMillis(delaySeconds);
        recordPendingEvent(event, now, retryCount, nextRetryTime);
        sendToRetry(event, retryCount, delaySeconds, reason);
    }

    private void sendToMain(CacheInvalidationEvent event, int retryCount) {
        event.setRetryCount(retryCount);
        rabbitTemplate.convertAndSend(
                CACHE_INVALIDATION_EXCHANGE,
                CACHE_INVALIDATION_ROUTING_KEY,
                event,
                message -> {
                    message.getMessageProperties().setHeader(RETRY_HEADER, retryCount);
                    return message;
                }
        );
    }

    private void sendToRetry(CacheInvalidationEvent event, int retryCount, long delaySeconds, String reason) {
        event.setRetryCount(retryCount);
        event.setLastError(reason);
        rabbitTemplate.convertAndSend(
                CACHE_INVALIDATION_EXCHANGE,
                CACHE_INVALIDATION_RETRY_ROUTING_KEY,
                event,
                message -> {
                    message.getMessageProperties().setHeader(RETRY_HEADER, retryCount);
                    message.getMessageProperties().setExpiration(String.valueOf(TimeUnit.SECONDS.toMillis(delaySeconds)));
                    return message;
                }
        );
    }

    private void recordPendingEvent(CacheInvalidationEvent event, long now, int retryCount, long nextRetryTime) {
        String eventId = event.getEventId();
        String pendingKey = CACHE_INVALIDATION_PENDING_KEY + eventId;
        Map<String, String> data = new HashMap<>(12);
        data.put("eventId", eventId);
        data.put("businessType", defaultValue(event.getBusinessType()));
        data.put("businessId", defaultValue(event.getBusinessId()));
        data.put("cacheKey", defaultValue(event.getCacheKey()));
        data.put("operation", defaultValue(event.getOperation()));
        data.put("retryCount", String.valueOf(retryCount));
        data.put("createdAt", String.valueOf(event.getCreatedAt() == null ? now : event.getCreatedAt()));
        data.put("nextRetryTime", String.valueOf(nextRetryTime));
        data.put("lastError", defaultValue(event.getLastError()));

        stringRedisTemplate.opsForHash().putAll(pendingKey, data);
        stringRedisTemplate.expire(pendingKey, PENDING_TTL_MINUTES, TimeUnit.MINUTES);
        stringRedisTemplate.opsForZSet().add(CACHE_INVALIDATION_PENDING_IDX_KEY, eventId, nextRetryTime);
    }

    private void clearPendingEvent(String eventId) {
        stringRedisTemplate.delete(CACHE_INVALIDATION_PENDING_KEY + eventId);
        stringRedisTemplate.opsForZSet().remove(CACHE_INVALIDATION_PENDING_IDX_KEY, eventId);
    }

    private void moveToDead(String eventId, String reason) {
        String pendingKey = CACHE_INVALIDATION_PENDING_KEY + eventId;
        Map<Object, Object> pendingData = stringRedisTemplate.opsForHash().entries(pendingKey);
        clearPendingEvent(eventId);

        if (pendingData != null && !pendingData.isEmpty()) {
            pendingData.put("deadReason", defaultValue(reason));
            pendingData.put("deadAt", String.valueOf(System.currentTimeMillis()));
            String deadKey = CACHE_INVALIDATION_PENDING_KEY + "dead:" + eventId;
            Map<String, String> deadMap = new HashMap<>();
            for (Map.Entry<Object, Object> entry : pendingData.entrySet()) {
                deadMap.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            stringRedisTemplate.opsForHash().putAll(deadKey, deadMap);
            stringRedisTemplate.expire(deadKey, 7, TimeUnit.DAYS);
        }
        stringRedisTemplate.opsForSet().add(CACHE_INVALIDATION_DEAD_KEY, eventId);
        rabbitTemplate.convertAndSend(CACHE_INVALIDATION_EXCHANGE, CACHE_INVALIDATION_DEAD_ROUTING_KEY, eventId);
    }

    private CacheInvalidationEvent toEvent(Map<Object, Object> data) {
        try {
            CacheInvalidationEvent event = new CacheInvalidationEvent();
            event.setEventId(str(data.get("eventId")));
            event.setBusinessType(str(data.get("businessType")));
            event.setBusinessId(str(data.get("businessId")));
            event.setCacheKey(str(data.get("cacheKey")));
            event.setOperation(str(data.get("operation")));
            event.setRetryCount(parseInt(data.get("retryCount")));
            event.setCreatedAt(parseLong(data.get("createdAt")));
            event.setNextRetryTime(parseLong(data.get("nextRetryTime")));
            event.setLastError(str(data.get("lastError")));
            return event;
        } catch (Exception e) {
            return null;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private String defaultValue(String value) {
        return value == null ? "" : value;
    }

    private String buildIdempotentEventKey(CacheInvalidationEvent event) {
        String rawKey = defaultValue(event.getBusinessType()) + "|"
                + defaultValue(event.getBusinessId()) + "|"
                + defaultValue(event.getCacheKey()) + "|"
                + defaultValue(event.getOperation()) + "|"
                + defaultValue(event.getEventId());
        return CACHE_INVALIDATION_IDEMPOTENT_KEY + DigestUtil.md5Hex(rawKey);
    }
}
