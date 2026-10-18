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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 高并发下"逻辑过期方案"验证测试
 *
 * 输出约定（每 1 个请求都会打印 1 行，方便你一眼看出哪次查询了数据库）：
 *  - 查数据库：打印很长的醒目句子  "★★★ 数据库已查询（第 N 次）……★★★"
 *  - 没查数据库：只打印两三个字  "[请求 037/100] 没查"
 *
 * 预期结果（缓存预热后已"逻辑过期"的情况下，100 个请求同时涌入）：
 *  - 100 行"没查"：所有请求都直接返回旧缓存数据，没有请求阻塞等待；
 *  - 只出现 1 行长句：唯一抢到锁的请求触发的后台重建任务，真实查了 1 次数据库。
 *
 * 运行前：请确保 Redis（your-redis-host:6379）已启动。
 */
@SpringBootTest
class ShopLogicalExpireConcurrencyTest {

    /** 并发请求数 */
    private static final int THREADS = 100;
    /** 被测商铺 id（测试数据，无需真实存在于数据库） */
    private static final Long SHOP_ID = 1L;
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

    /** 并发阶段数据库（mock Mapper 的 selectById）被真实调用的次数 */
    private final AtomicInteger dbHitCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        // 1. 计数清零，并清掉可能残留的缓存与锁
        dbHitCount.set(0);
        stringRedisTemplate.delete("cache:shop:" + SHOP_ID);
        stringRedisTemplate.delete(LOCK_KEY);

        // 2. mock 数据库查询：每被调用一次 = 真实查了一次数据库，打印醒目长句
        Shop shop = new Shop();
        shop.setId(SHOP_ID);
        shop.setName("逻辑过期测试商铺");
        when(shopMapper.selectById(any(Serializable.class))).thenAnswer(invocation -> {
            int hitNo = dbHitCount.incrementAndGet();
            System.out.println("★★★ 数据库已查询（本次测试第 " + hitNo + " 次）！真实的 SELECT * FROM tb_shop WHERE id = "
                    + SHOP_ID + " 被执行 ★★★");
            return shop;
        });

        // 3. 预热：模拟项目启动时把热点商铺写入缓存，并把"逻辑过期时间"设为 5 秒前（即缓存已经过期）
        //    （saveShop2Redis 内部会查一次数据库，属于预热阶段，不计入统计，稍后清零）
        shopService.saveShop2Redis(SHOP_ID, -5L);
        System.out.println(">>> 预热完成：缓存中已存在【逻辑过期】的旧数据，逻辑过期时间 = 5 秒前");
        System.out.println(">>> 预热阶段的数据库查询不计入统计，计数已清零");
        dbHitCount.set(0);
    }

    @Test
    void concurrentQueryWithLogicalExpireOnlyRebuildOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch readyLatch = new CountDownLatch(THREADS); // 标记"线程已就绪"
        CountDownLatch startLatch = new CountDownLatch(1);        // 统一发令枪
        CountDownLatch doneLatch = new CountDownLatch(THREADS);   // 标记"全部执行完毕"
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 1; i <= THREADS; i++) {
            final int requestNo = i;
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // 所有线程就绪后同时放行，制造同一瞬间的并发
                    Shop result = shopService.queryWithLogicalExpire(SHOP_ID);
                    if (result == null) {
                        System.out.printf("[请求 %03d/%d] 缓存无数据%n", requestNo, THREADS);
                    } else {
                        // 逻辑过期方案：无论是否触发重建，请求都直接返回旧数据，本次请求没查库
                        System.out.printf("[请求 %03d/%d] 没查%n", requestNo, THREADS);
                    }
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        readyLatch.await();
        System.out.println(">>> 发令枪响！100 个请求同时涌入（缓存已逻辑过期）……");
        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "测试超时：疑似死锁或线程卡死");
        assertNull(error.get(), "并发请求中出现异常：" + error.get());

        // 等待后台重建任务完成：重建线程执行完毕会在 finally 中释放（删除）锁 key
        long deadline = System.currentTimeMillis() + 10_000;
        while (Boolean.TRUE.equals(stringRedisTemplate.hasKey(LOCK_KEY)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        // 断言 + 汇总
        int hits = dbHitCount.get();
        System.out.println("================= 测试结果汇总 =================");
        System.out.println("并发请求总数：100 次，每个请求都打印了自己的那一行（见上方输出）");
        System.out.println("后台真实查询数据库的次数：" + hits + " 次");
        System.out.println("100 个请求全部直接返回旧缓存数据，没有请求查库、没有请求阻塞等待");
        assertEquals(1, hits, "逻辑过期方案失效：100 个并发请求下数据库被查询了 " + hits + " 次（预期 1 次）");
    }
}
