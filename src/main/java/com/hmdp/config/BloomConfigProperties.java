package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BloomConfigProperties {

    @Value("${hmdp.cache.bloom.enabled:true}")
    private boolean enabled;

    @Value("${hmdp.cache.bloom.shop-expected-insertions:200000}")
    private long shopExpectedInsertions;

    @Value("${hmdp.cache.bloom.shop-false-positive-rate:0.01}")
    private double shopFalsePositiveRate;

    @Value("${hmdp.cache.bloom.rebuild-cron:0 0 4 * * ?}")
    private String rebuildCron;

    public boolean isEnabled() {
        return enabled;
    }

    public long getShopExpectedInsertions() {
        return shopExpectedInsertions;
    }

    public double getShopFalsePositiveRate() {
        return shopFalsePositiveRate;
    }

    public String getRebuildCron() {
        return rebuildCron;
    }
}