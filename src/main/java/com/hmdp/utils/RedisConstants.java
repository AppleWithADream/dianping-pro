package com.hmdp.utils;

/**
 * Redis 常量类：集中管理项目中所有 Redis key 前缀与 TTL，避免魔法值散落各处。
 * 命名统一为 "业务:标识:" 的冒号风格，便于按模块批量管理。
 */
public class RedisConstants {
    // 登录验证码 key 前缀：login:code:{手机号}
    public static final String LOGIN_CODE_KEY = "login:code:";
    // 验证码有效期（分钟）
    public static final Long LOGIN_CODE_TTL = 2L;
    // 登录令牌 key 前缀：login:token:{token}
    public static final String LOGIN_USER_KEY = "login:token:";
    // 登录用户信息有效期（秒），后续由拦截器滑动续期
    public static final Long LOGIN_USER_TTL = 36000L;

    // 缓存空值的短 TTL（分钟），用于解决缓存穿透
    public static final Long CACHE_NULL_TTL = 2L;

    // 商铺缓存 TTL（分钟）
    public static final Long CACHE_SHOP_TTL = 30L;
    // 商铺缓存 key 前缀：cache:shop:{id}
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    // 商铺缓存重建互斥锁 key 前缀：lock:shop:{id}
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    // 锁的持有超时时间（秒），防止死锁
    public static final Long LOCK_SHOP_TTL = 10L;

    // 秒杀库存 key 前缀：seckill:stock:{券id}
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    // 笔记点赞 ZSet key 前缀：blog:liked:{笔记id}，value=用户id，score=点赞时间
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    // 收件箱 Feed 流 key 前缀：feed:{粉丝id}，存被关注用户发布的笔记
    public static final String FEED_KEY = "feed:";
    // 商铺地理位置 GEO key 前缀：shop:geo:{类型id}
    public static final String SHOP_GEO_KEY = "shop:geo:";
    // 用户签到 Bitmap key 前缀：sign:{用户id}:{yyyyMM}
    public static final String USER_SIGN_KEY = "sign:";
}
