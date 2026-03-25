package com.hmdp.task;

import com.hmdp.service.CacheInvalidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class CacheInvalidationCompensationTask {

    @Resource
    private CacheInvalidationService cacheInvalidationService;

    @Value("${hmdp.cache.invalidation.compensation.batch-size:100}")
    private int batchSize;

    @Value("${hmdp.cache.invalidation.compensation.max-retry:8}")
    private int maxRetry;

    @Value("${hmdp.cache.invalidation.compensation.base-delay-seconds:30}")
    private long baseDelaySeconds;

    @Scheduled(cron = "${hmdp.cache.invalidation.compensation.cron:*/30 * * * * ?}")
    public void compensatePendingInvalidations() {
        try {
            cacheInvalidationService.compensatePendingInvalidations(batchSize, maxRetry, baseDelaySeconds);
        } catch (Exception e) {
            log.error("执行缓存失效补偿任务异常", e);
        }
    }
}
