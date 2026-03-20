package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.config.CaffeineConfigProperties;
import com.hmdp.config.BloomConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private static final String LOCAL_NULL_VALUE = "__NULL__";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final BloomConfigProperties bloomConfigProperties;
    private final CaffeineConfigProperties caffeineConfigProperties;
    private final Cache<String, Object> localL1Cache;

    public CacheClient(
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            BloomConfigProperties bloomConfigProperties,
            CaffeineConfigProperties caffeineConfigProperties,
            Cache<String, Object> localL1Cache) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.bloomConfigProperties = bloomConfigProperties;
        this.caffeineConfigProperties = caffeineConfigProperties;
        this.localL1Cache = localL1Cache;
    }

    public <R, ID> R queryWithL1L2PassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long l1Time,
            TimeUnit l1Unit,
            Long l2Time,
            TimeUnit l2Unit) {
        String key = keyPrefix + id;
        R local = readFromLocalCache(key, type);
        if (local != null || isLocalNullValue(key)) {
            return local;
        }

        R r = queryWithPassThrough(keyPrefix, id, type, dbFallback, l2Time, l2Unit);
        writeToLocalCache(key, r, l1Time, l1Unit);
        return r;
    }

    public <R, ID> R queryWithL1L2BloomPassThrough(
            String keyPrefix,
            String bloomKey,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long l1Time,
            TimeUnit l1Unit,
            Long l2Time,
            TimeUnit l2Unit) {
        String key = keyPrefix + id;
        R local = readFromLocalCache(key, type);
        if (local != null || isLocalNullValue(key)) {
            return local;
        }

        R r = queryWithBloomPassThrough(keyPrefix, bloomKey, id, type, dbFallback, l2Time, l2Unit);
        writeToLocalCache(key, r, l1Time, l1Unit);
        return r;
    }

    public <R> R querySingleKeyWithL1L2PassThrough(
            String key,
            Class<R> type,
            Function<String, R> dbFallback,
            Long l1Time,
            TimeUnit l1Unit,
            Long l2Time,
            TimeUnit l2Unit) {
        R local = readFromLocalCache(key, type);
        if (local != null || isLocalNullValue(key)) {
            return local;
        }

        R r = querySingleKeyWithPassThrough(key, type, dbFallback, l2Time, l2Unit);
        writeToLocalCache(key, r, l1Time, l1Unit);
        return r;
    }

    public <R> List<R> querySingleKeyListWithL1L2PassThrough(
            String key,
            Class<R> elementType,
            Function<String, List<R>> dbFallback,
            Long l1Time,
            TimeUnit l1Unit,
            Long l2Time,
            TimeUnit l2Unit) {
        List<R> local = readListFromLocalCache(key, elementType);
        if (local != null || isLocalNullValue(key)) {
            return local;
        }

        List<R> r = querySingleKeyListWithPassThrough(key, elementType, dbFallback, l2Time, l2Unit);
        writeToLocalCache(key, r, l1Time, l1Unit);
        return r;
    }

    public void invalidateLocalCache(String key) {
        if (!caffeineConfigProperties.isEnabled()) {
            return;
        }
        localL1Cache.invalidate(key);
    }

    private <R> R querySingleKeyWithPassThrough(
            String key,
            Class<R> type,
            Function<String, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(key);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    private <R> List<R> querySingleKeyListWithPassThrough(
            String key,
            Class<R> elementType,
            Function<String, List<R>> dbFallback,
            Long time,
            TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toList(json, elementType);
        }
        if (json != null) {
            return null;
        }

        List<R> r = dbFallback.apply(key);
        if (r == null || r.isEmpty()) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    private <R> R readFromLocalCache(String key, Class<R> type) {
        if (!caffeineConfigProperties.isEnabled()) {
            return null;
        }
        Object cached = localL1Cache.getIfPresent(key);
        if (!(cached instanceof LocalCacheValue)) {
            return null;
        }

        LocalCacheValue localCacheValue = (LocalCacheValue) cached;
        if (localCacheValue.expired()) {
            localL1Cache.invalidate(key);
            return null;
        }
        if (LOCAL_NULL_VALUE.equals(localCacheValue.value)) {
            return null;
        }
        return JSONUtil.toBean(localCacheValue.value, type);
    }

    private <R> List<R> readListFromLocalCache(String key, Class<R> elementType) {
        if (!caffeineConfigProperties.isEnabled()) {
            return null;
        }
        Object cached = localL1Cache.getIfPresent(key);
        if (!(cached instanceof LocalCacheValue)) {
            return null;
        }

        LocalCacheValue localCacheValue = (LocalCacheValue) cached;
        if (localCacheValue.expired()) {
            localL1Cache.invalidate(key);
            return null;
        }
        if (LOCAL_NULL_VALUE.equals(localCacheValue.value)) {
            return null;
        }
        return JSONUtil.toList(localCacheValue.value, elementType);
    }

    private boolean isLocalNullValue(String key) {
        if (!caffeineConfigProperties.isEnabled()) {
            return false;
        }
        Object cached = localL1Cache.getIfPresent(key);
        if (!(cached instanceof LocalCacheValue)) {
            return false;
        }

        LocalCacheValue localCacheValue = (LocalCacheValue) cached;
        if (localCacheValue.expired()) {
            localL1Cache.invalidate(key);
            return false;
        }
        return LOCAL_NULL_VALUE.equals(localCacheValue.value);
    }

    private void writeToLocalCache(String key, Object value, Long time, TimeUnit unit) {
        if (!caffeineConfigProperties.isEnabled()) {
            return;
        }

        Long expireAt = System.currentTimeMillis() + unit.toMillis(time);
        if (value == null) {
            localL1Cache.put(
                    key,
                    new LocalCacheValue(LOCAL_NULL_VALUE,
                            System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(RedisConstants.CACHE_NULL_L1_TTL_SECONDS)));
            return;
        }
        localL1Cache.put(key, new LocalCacheValue(JSONUtil.toJsonStr(value), expireAt));
    }

    private static class LocalCacheValue {
        private final String value;
        private final long expireAtMillis;

        private LocalCacheValue(String value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        private boolean expired() {
            return System.currentTimeMillis() >= expireAtMillis;
        }
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中是否是空值
        if (json != null) {
            // 返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.数据库中不存在，返回错误
        if (r == null) {
            //将空值写入redis以应对缓存击穿
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入redis
        this.set(key,r,time,unit);
        //7.返回
        return r;
    }

    public <R, ID> R queryWithBloomPassThrough(
            String keyPrefix, String bloomKey, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        if (!bloomConfigProperties.isEnabled()) {
            return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
        }

        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(bloomKey);
        try {
            if (!bloomFilter.isExists()) {
                log.warn("Bloom filter {} is not initialized, fallback to normal pass-through", bloomKey);
                return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
            }
            if (!bloomFilter.contains(String.valueOf(id))) {
                log.debug("Bloom filtered invalid id={}, bloomKey={}", id, bloomKey);
                return null;
            }
        } catch (Exception e) {
            log.warn("Bloom check failed, fallback to normal pass-through, bloomKey={}, id={}", bloomKey, id, e);
            return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
        }

        return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
    }

    public boolean addToBloom(String bloomKey, Object value) {
        if (!bloomConfigProperties.isEnabled() || value == null) {
            return false;
        }

        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(bloomKey);
        try {
            if (!bloomFilter.isExists()) {
                bloomFilter.tryInit(
                        bloomConfigProperties.getShopExpectedInsertions(),
                        bloomConfigProperties.getShopFalsePositiveRate());
            }
            return bloomFilter.add(String.valueOf(value));
        } catch (Exception e) {
            log.warn("Add value to bloom failed, bloomKey={}, value={}", bloomKey, value, e);
            return false;
        }
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.存在，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return r;
        }

        //5.2过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);


        //6.2判断是否获取锁成功
        if(isLock){
            // 6.3成功，开启独立线程，实现缓存重建;注意，获取锁成功后应再次检查redis缓存是否过期，即DoubleCheck
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }
        //6.4失败，返回过期的商铺信息


        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
