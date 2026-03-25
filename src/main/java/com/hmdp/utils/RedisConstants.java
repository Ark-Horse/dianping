package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
    public static final Long CACHE_SHOP_L1_TTL_SECONDS = 5L;
    public static final Long CACHE_SHOP_TYPE_L1_TTL_SECONDS = 1800L;
    public static final Long CACHE_NULL_L1_TTL_SECONDS = 120L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:";
    public static final String BLOOM_SHOP_ID_KEY = "bloom:shop:id";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SECKILL_PENDING_ORDER_KEY = "seckill:pending:order:";
    public static final String SECKILL_PENDING_ORDER_IDX_KEY = "seckill:pending:idx";
    public static final String SECKILL_PENDING_ORDER_DEAD_KEY = "seckill:pending:dead";

    public static final String CACHE_INVALIDATION_PENDING_KEY = "cache:invalidation:pending:";
    public static final String CACHE_INVALIDATION_PENDING_IDX_KEY = "cache:invalidation:pending:idx";
    public static final String CACHE_INVALIDATION_DEAD_KEY = "cache:invalidation:dead";
    public static final String CACHE_INVALIDATION_IDEMPOTENT_KEY = "cache:invalidation:idempotent:";
}
