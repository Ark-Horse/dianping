package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CaffeineConfigProperties {

    @Value("${hmdp.cache.caffeine.enabled:true}")
    private boolean enabled;

    @Value("${hmdp.cache.caffeine.max-size:10000}")
    private long maxSize;

    @Value("${hmdp.cache.caffeine.default-ttl-seconds:300}")
    private long defaultTtlSeconds;

    public boolean isEnabled() {
        return enabled;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }
}