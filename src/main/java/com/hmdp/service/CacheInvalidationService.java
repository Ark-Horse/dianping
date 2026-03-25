package com.hmdp.service;

import com.hmdp.dto.CacheInvalidationEvent;

public interface CacheInvalidationService {

    void publishInvalidationEvent(String businessType, String businessId, String cacheKey, String operation);

    void handleInvalidationEvent(CacheInvalidationEvent event, Integer messageRetryCount);

    void compensatePendingInvalidations(int batchSize, int maxRetry, long baseDelaySeconds);
}
