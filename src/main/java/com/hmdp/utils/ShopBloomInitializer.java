package com.hmdp.utils;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.BloomConfigProperties;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ShopBloomInitializer implements ApplicationRunner {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private BloomConfigProperties bloomConfigProperties;

    @Override
    public void run(ApplicationArguments args) {
        rebuildBloomInternal(false);
    }

    @Scheduled(cron = "${hmdp.cache.bloom.rebuild-cron:0 0 4 * * ?}")
    public void scheduledRebuild() {
        rebuildBloomInternal(true);
    }

    private void rebuildBloomInternal(boolean forceRebuild) {
        if (!bloomConfigProperties.isEnabled()) {
            log.info("Bloom filter is disabled by config, skip shop bloom initialization");
            return;
        }

        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(RedisConstants.BLOOM_SHOP_ID_KEY);
        try {
            if (forceRebuild && bloomFilter.isExists()) {
                bloomFilter.delete();
            }
            if (!bloomFilter.isExists()) {
                bloomFilter.tryInit(
                        bloomConfigProperties.getShopExpectedInsertions(),
                        bloomConfigProperties.getShopFalsePositiveRate());
            }

            List<Object> idObjs = shopMapper.selectObjs(new QueryWrapper<Shop>().select("id"));
            if (CollUtil.isEmpty(idObjs)) {
                log.info("Shop bloom initialized with empty id set");
                return;
            }

            Set<String> shopIds = idObjs.stream().map(String::valueOf).collect(Collectors.toSet());
            for (String shopId : shopIds) {
                bloomFilter.add(shopId);
            }
            log.info("Shop bloom rebuilt, size={}, forceRebuild={}", shopIds.size(), forceRebuild);
        } catch (Exception e) {
            log.error("Failed to initialize shop bloom filter", e);
        }
    }
}