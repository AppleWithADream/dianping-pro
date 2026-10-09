package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 设置缓存
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 逻辑过期缓存
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 通过缓存空值解决缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id,Class<R> type,
                                         Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 先查 Redis 缓存，key = cache:shop:{id}，value 为商铺对象的 JSON 字符串
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 缓存命中（注意 isNotBlank：空串 "" 也视为未命中，为后续缓存空值方案留出余地）
        if (StrUtil.isNotBlank(json)) {
            // 3. 把 JSON 字符串反序列化为 Shop 对象后直接返回，不再访问数据库
            return JSONUtil.toBean(json, type);
        }
        // 3.1. 缓存中存在该 id，但值为空串 ""，返回失败
        if (json != null){
            return null;
        }
        // 4. 缓存未命中 → 查询数据库（MyBatis-Plus 的 getById）
        R r = dbFallback.apply(id);
        // 5. 数据库中也不存在，说明该 id 非法，返回业务失败提示
        if (r == null) {
            // 5.1. 缓存中不存在该 id，设置空值缓存，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"", 2, TimeUnit.MINUTES);
            return null;
        }
        // 6. 数据库命中 → 将商铺对象序列化为 JSON 写入 Redis 缓存，供后续请求直接命中
        this.set(key, r, time, unit);
        // 7. 返回查询结果
        return r;
    }

    // 逻辑过期缓存解决缓存击穿
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        //    （RedisData = 业务数据 data + 逻辑过期时间 expireTime，封装细节见 saveShop2Redis）
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 缓存中不存在该 id（热点数据未预热，或商铺本就不存在）→ 返回失败
        //    注意：逻辑过期方案假设热点数据已提前预热进缓存，因此这里不查数据库、不重建缓存
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 3. 缓存存在 → 先反序列化为 RedisData，再取出其中的业务数据 Shop
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 4. 逻辑过期时间未到 → 缓存仍有效，直接返回，不访问数据库
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }
        // 5. 逻辑过期时间已到 → 尝试获取互斥锁，保证同一时刻只有一个线程去后台重建缓存
        String lockKey = "lock:shop:" + id;
        boolean isLocked = tryLock(lockKey);
        if (isLocked) {
            // 5.1. 获取锁成功 → 提交到线程池异步重建缓存：重新查库并写入带新逻辑过期时间的数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 5.1.1. 重新查库并写入带新逻辑过期时间的数据
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 5.2. 无论重建成功与否都要释放锁，保证后续过期请求能再次触发重建
                    unlock(lockKey);
                }
            });
        }
        // 5.3. 获取锁失败 → 说明已有其他线程在重建，本线程不需要做任何事
        // 6. 返回查询结果：此刻返回的是"逻辑过期但可用的旧数据"，
        //    以短暂的数据不一致换取接口的高可用——这正是逻辑过期与互斥锁方案的本质区别
        return r;
    }

    // 获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 判断是否获取成功
        return BooleanUtil.isTrue(flag);
    }
    // 删除锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
