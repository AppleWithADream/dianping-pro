package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 商铺服务实现类
 *
 * 本类是 Redis 缓存实战的演示载体：
 *   - 核心思路：利用 Redis 为商铺查询接口加一层缓存，减轻数据库访问压力；
 *   - 当前 queryById 是"缓存读写"的初级版本，仅演示最基础的缓存流程，
 *     尚未引入缓存过期时间、缓存穿透（空值缓存）、缓存击穿（互斥锁/逻辑过期）等进阶处理，
 *     这些会在后续学习中逐步补充完善。
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    // Spring 容器注入的 StringRedisTemplate：以字符串形式读写 Redis，
    // 项目中约定商铺缓存的 key 统一为 "cache:shop:" + 商铺id，便于按业务模块管理。
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息（Redis 缓存 + 数据库兜底的"缓存旁路"模式）
     * @param id 商铺id
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);


        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /**
     * 缓存穿透
     * @param id 商铺id
     * @return 商铺信息
     */
    public Shop queryWithPassThrough(Long id) {
        // 1. 先查 Redis 缓存，key = cache:shop:{id}，value 为商铺对象的 JSON 字符串
        String shopJosn = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2. 缓存命中（注意 isNotBlank：空串 "" 也视为未命中，为后续缓存空值方案留出余地）
        if (StrUtil.isNotBlank(shopJosn)) {
            // 3. 把 JSON 字符串反序列化为 Shop 对象后直接返回，不再访问数据库
            return JSONUtil.toBean(shopJosn, Shop.class);
        }
        // 3.1. 缓存中存在该 id，但值为空串 ""，返回失败
        if (shopJosn != null){
            return null;
        }
        // 4. 缓存未命中 → 查询数据库（MyBatis-Plus 的 getById）
        Shop shop = getById(id);
        // 5. 数据库中也不存在，说明该 id 非法，返回业务失败提示
        if (shop == null) {
            // 5.1. 缓存中不存在该 id，设置空值缓存，防止缓存穿透
            stringRedisTemplate.opsForValue().set("cache:shop:" + id,"", 2, TimeUnit.MINUTES);
            return null;
        }
        // 6. 数据库命中 → 将商铺对象序列化为 JSON 写入 Redis 缓存，供后续请求直接命中
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        // 7. 返回查询结果
        return shop;
    }

    /**
     * 根据id查询商铺信息 —— 互斥锁方案解决缓存击穿
     * @param id 商铺id
     * @return 商铺信息；商铺不存在或命中空值缓存时返回 null
     */
    public Shop queryWithMutex(Long id) {
        // 1. 先查 Redis 缓存，key = cache:shop:{id}，value 为商铺对象的 JSON 字符串
        String shopJosn = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2. 缓存命中（注意 isNotBlank：空串 "" 也视为未命中，为后续缓存空值方案留出余地）
        if (StrUtil.isNotBlank(shopJosn)) {
            // 3. 把 JSON 字符串反序列化为 Shop 对象后直接返回，不再访问数据库
            return JSONUtil.toBean(shopJosn, Shop.class);
        }
        // 3.1. 缓存中存在该 id，但值为空串 ""，返回失败
        if (shopJosn != null){
            return null;
        }
        // 4.1. 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLocked = tryLock(lockKey);
            if (!isLocked) {
                // 4.2. 获取锁失败，等待重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.3. 获取锁成功，查询数据库
            shop = getById(id);
            // 5. 数据库中也不存在，说明该 id 非法，返回业务失败提示
            if (shop == null) {
                // 5.1. 缓存中不存在该 id，设置空值缓存，防止缓存穿透
                stringRedisTemplate.opsForValue().set("cache:shop:" + id,"", 2, TimeUnit.MINUTES);
                return null;
            }
            // 6. 数据库命中 → 将商铺对象序列化为 JSON 写入 Redis 缓存，供后续请求直接命中
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 4.4. 释放互斥锁
            unlock(lockKey);
        }
        // 7. 返回查询结果
        return shop;
    }


    /**
     * 根据id查询商铺信息 —— 逻辑过期方案解决缓存击穿
     * @param id 商铺id
     * @return 商铺信息；缓存中不存在该 id 时返回 null（逻辑过期后仍返回旧数据，而非 null）
     */
    // 线程池，用于缓存重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id) {
        // 1. 先查 Redis 缓存：key = cache:shop:{id}，value 是 RedisData 的 JSON 字符串
        //    （RedisData = 业务数据 data + 逻辑过期时间 expireTime，封装细节见 saveShop2Redis）
        String shopJosn = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        // 2. 缓存中不存在该 id（热点数据未预热，或商铺本就不存在）→ 返回失败
        //    注意：逻辑过期方案假设热点数据已提前预热进缓存，因此这里不查数据库、不重建缓存
        if (StrUtil.isBlank(shopJosn)) {
            return null;
        }
        // 3. 缓存存在 → 先反序列化为 RedisData，再取出其中的业务数据 Shop
        //    注意：saveShop2Redis 中 data 存的是 Shop 对象，序列化后是 JSON 对象，
        //    反序列化回来 getData() 返回的是 JSONObject（强转 String 会抛 ClassCastException）
        RedisData redisData = JSONUtil.toBean(shopJosn, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        // 4. 逻辑过期时间未到 → 缓存仍有效，直接返回，不访问数据库
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 5. 逻辑过期时间已到 → 尝试获取互斥锁，保证同一时刻只有一个线程去后台重建缓存
        String lockKey = "lock:shop:" + id;
        boolean isLocked = tryLock(lockKey);
        if (isLocked) {
            // 5.1. 获取锁成功 → 提交到线程池异步重建缓存：重新查库并写入带新逻辑过期时间的数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 5.1.1. 重新查库并写入带新逻辑过期时间的数据
                    this.saveShop2Redis(id, 20L);
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
        return shop;
    }

    /**
     * 更新商铺信息
     * @param shop 商铺信息
     * @return 更新结果
     */
    @Override
    @Transactional   // 事务管理，确保数据库更新和缓存删除操作要么都成功，要么都失败
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除 Redis 缓存
        stringRedisTemplate.delete("cache:shop:" + shop.getId());
        return Result.ok();
    }


    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            // 未提供排序依据，按数据库默认顺序返回
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );

        // 4.解析出id
        if (results == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok();
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);

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
    // 将商铺信息保存到 Redis 中，并设置逻辑过期时间
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }

        Thread.sleep(200);

        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

}
