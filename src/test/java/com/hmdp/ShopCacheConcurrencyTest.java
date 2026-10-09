package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 高并发缓存击穿验证测试
 *
 * 目的：不需要真实压测工具，用 100 个线程模拟"缓存过期瞬间的大量并发请求"，
 * 对比互斥锁方案（queryWithMutex）与无锁方案（queryWithPassThrough）打向数据库的次数。
 *
 * 原理：
 *  - ShopMapper 被 mock 掉，每被调用一次（代表真实世界里的一次数据库查询）计数器 +1；
 *  - 模拟的"数据库查询"耗时 30ms，确保等锁重试、缓存重建等真实路径都会被触发；
 *  - Redis 使用真实的（application.yaml 中配置的连接），互斥锁的原子性是真实行为。
 *
 * 运行前：请确保 Redis（192.168.100.128:6379）已启动。
 * 运行后观察控制台输出：
 *  >>> [无锁对照]   100 个并发请求，数据库实际被查询 N 次   → N 应接近 100（缓存击穿）
 *  >>> [互斥锁]     100 个并发请求，数据库实际被查询 N 次   → N 应接近 1（击穿被解决）
 */
@SpringBootTest
class ShopCacheConcurrencyTest {

    /** 并发线程数：模拟同一瞬间涌入的请求量 */
    private static final int THREADS = 100;
    /** 被测商铺 id（测试数据，无需真实存在于数据库） */
    private static final Long SHOP_ID = 1L;
    /** 与 ShopServiceImpl 中约定一致的缓存 key */
    private static final String CACHE_KEY = "cache:shop:" + SHOP_ID;
    /** 与 ShopServiceImpl 中约定一致的互斥锁 key */
    private static final String LOCK_KEY = "lock:shop:" + SHOP_ID;

    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** mock 掉 ShopMapper：测试不需要真实 MySQL，同时用它统计"数据库被查询的次数" */
    @MockBean
    private ShopMapper shopMapper;
    /** mock 掉 DataSource：本测试只依赖 Redis，避免启动时强连 MySQL */
    @MockBean
    private DataSource dataSource;

    /** 数据库（mock Mapper 的 selectById）被真实调用的总次数 */
    private final AtomicInteger dbHitCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        // 1. 计数清零
        dbHitCount.set(0);
        // 2. 清掉可能残留的缓存与锁，确保每个用例都从"缓存未命中"开始
        stringRedisTemplate.delete(CACHE_KEY);
        stringRedisTemplate.delete(LOCK_KEY);
        // 3. 配置 mock：selectById 每次被调用 = 数据库被查了一次
        Shop shop = new Shop();
        shop.setId(SHOP_ID);
        shop.setName("并发测试店铺");
        when(shopMapper.selectById(any(Serializable.class))).thenAnswer(invocation -> {
            // 模拟真实数据库 30ms 的查询耗时，让"等锁 → 重试 → 命中缓存"的真实路径被触发
            Thread.sleep(30);
            dbHitCount.incrementAndGet();
            return shop;
        });
    }

    /**
     * 实验组：互斥锁方案。
     * 100 个并发请求同时查询同一商铺，缓存已清空（模拟刚过期），
     * 预期只有 1 个线程真正查到数据库，其余线程在锁释放后直接命中重建好的缓存。
     */
    @Test
    void concurrentQueryWithMutexOnlyHitsDbOnce() throws Exception {
        List<Shop> results = fireConcurrentQueries(shopService::queryWithMutex);

        // 所有请求都应成功返回商铺信息（不存在查询失败）
        assertEquals(THREADS, results.size(), "应有 100 个请求全部返回");
        results.forEach(shop -> assertNotNull(shop, "互斥锁方案下每个请求都应成功返回商铺"));

        // 关键断言：数据库只应被查询约 1 次（放宽到 3 次以内，避免机器调度抖动导致偶发失败）
        int hits = dbHitCount.get();
        System.out.println(">>> [互斥锁] " + THREADS + " 个并发请求，数据库实际被查询 " + hits + " 次（理论值：1 次）");
        assertTrue(hits <= 3, "互斥锁疑似失效：数据库被查询了 " + hits + " 次");
    }

    /**
     * 对照组：无互斥锁的"缓存穿透"基础方案（只有空值缓存）。
     * 同样的并发场景下没有锁的保护，预期几乎所有线程都会同时打向数据库 —— 即缓存击穿现场。
     */
    @Test
    void concurrentQueryWithoutLockHitsDbMassively() throws Exception {
        List<Shop> results = fireConcurrentQueries(shopService::queryWithPassThrough);

        assertEquals(THREADS, results.size(), "应有 100 个请求全部返回");
        results.forEach(shop -> assertNotNull(shop, "对照实验每个请求都应成功返回商铺"));

        int hits = dbHitCount.get();
        System.out.println(">>> [无锁对照] " + THREADS + " 个并发请求，数据库实际被查询 " + hits + " 次");
        assertTrue(hits > THREADS / 2, "对照实验应出现大面积穿透，数据库查询次数应接近 100，实际为 " + hits);
    }

    /**
     * 并发执行器：让 THREADS 个线程在同一瞬间（CountDownLatch 发令枪）调用同一查询方法。
     *
     * @param query 查询函数（queryWithMutex / queryWithPassThrough 的方法引用）
     * @return 所有线程的查询结果集合
     */
    private List<Shop> fireConcurrentQueries(Function<Long, Shop> query) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch readyLatch = new CountDownLatch(THREADS); // 标记"线程已就绪"
        CountDownLatch startLatch = new CountDownLatch(1);        // 统一发令枪
        CountDownLatch doneLatch = new CountDownLatch(THREADS);   // 标记"全部执行完毕"
        List<Shop> results = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // 所有线程就绪后同时放行，制造同一瞬间的并发
                    results.add(query.apply(SHOP_ID));
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        readyLatch.await();
        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "测试超时：疑似死锁或线程卡死");
        assertNull(error.get(), "并发请求中出现异常：" + error.get());
        return results;
    }
}
