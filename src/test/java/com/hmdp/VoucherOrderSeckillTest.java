package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀（Redis+Lua 资格判断阶段）并发测试
 *
 * 不用 Postman：直接用线程池模拟成百上千人同时点"抢购"按钮。
 *
 * 运行前：Redis(192.168.100.128:6379)、MySQL(127.0.0.1:3306) 都要启动。
 * 测试券id固定为 1010，跟真实数据隔离，测前会自动重置 Redis 里的库存和订单集合。
 */
@SpringBootTest
class VoucherOrderSeckillTest {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 测试专用优惠券id（数据库里不需要真有这张券，Lua只看Redis） */
    private static final Long VOUCHER_ID = 1010L;
    private static final String STOCK_KEY = "seckill:stock:" + VOUCHER_ID;
    private static final String ORDER_KEY = "seckill:order:" + VOUCHER_ID;

    /** 每个测试前：清场上一轮残留，重新铺库存 */
    @BeforeEach
    void cleanRedis() {
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
    }

    /** 给当前线程绑定登录用户（正常请求由拦截器做，测试里手动模拟） */
    private void mockLogin(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    /**
     * 测试1：串行走查，把三种返回结果都走一遍，肉眼对一遍逻辑
     * 库存放3张：1001买两次（第二次应被拦）、1002、1003买完，1004来时应提示库存不足
     */
    @Test
    void testSerialThreeCases() {
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "3");

        mockLogin(1001L);
        Result r1 = voucherOrderService.seckillVoucher(VOUCHER_ID);
        System.out.println(">>> 用户1001 第1次下单: success=" + r1.getSuccess() + ", data=" + r1.getData());

        Result r2 = voucherOrderService.seckillVoucher(VOUCHER_ID);
        System.out.println(">>> 用户1001 第2次下单: success=" + r2.getSuccess() + ", msg=" + r2.getErrorMsg());

        mockLogin(1002L);
        Result r3 = voucherOrderService.seckillVoucher(VOUCHER_ID);
        System.out.println(">>> 用户1002 下单:     success=" + r3.getSuccess() + ", data=" + r3.getData());

        mockLogin(1003L);
        Result r4 = voucherOrderService.seckillVoucher(VOUCHER_ID);
        System.out.println(">>> 用户1003 下单:     success=" + r4.getSuccess() + ", data=" + r4.getData());

        mockLogin(1004L);
        Result r5 = voucherOrderService.seckillVoucher(VOUCHER_ID);
        System.out.println(">>> 用户1004 下单:     success=" + r5.getSuccess() + ", msg=" + r5.getErrorMsg());

        String stockLeft = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        Long orderSize = stringRedisTemplate.opsForSet().size(ORDER_KEY);
        System.out.println(">>> 最终Redis库存=" + stockLeft + ", 已下单人数=" + orderSize);

        assertTrue(r1.getSuccess(), "1001第一次应该成功");
        assertTrue(!r2.getSuccess() && "请勿重复下单！".equals(r2.getErrorMsg()), "1001第二次应被一人一单拦截");
        assertTrue(r3.getSuccess() && r4.getSuccess(), "1002、1003应该成功");
        assertTrue(!r5.getSuccess() && "库存不足！".equals(r5.getErrorMsg()), "库存卖完后应提示库存不足");
        assertEquals("0", stockLeft, "库存应恰好扣到0");
        assertEquals(3L, orderSize, "应恰好3个人下单，不能超卖");
    }

    /**
     * 测试2：防超卖——100个不同用户同时抢10张券
     * 期望：恰好10人成功，其余90人全部"库存不足"，最终库存=0、集合=10人
     */
    @Test
    void testNoOversell() throws InterruptedException {
        final int STOCK = 10;
        final int USERS = 100;
        stringRedisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(STOCK));

        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch ready = new CountDownLatch(USERS); // 所有人先就位
        CountDownLatch begin = new CountDownLatch(1);     // 发令枪：一枪同时开抢
        CountDownLatch end = new CountDownLatch(USERS);   // 等所有人跑完

        AtomicInteger successCount = new AtomicInteger();      // 成功数
        AtomicInteger stockNotEnough = new AtomicInteger();    // "库存不足"数
        AtomicInteger duplicate = new AtomicInteger();         // "重复下单"数（本场景不该出现）
        AtomicInteger error = new AtomicInteger();             // 异常数

        for (long userId = 2001; userId < 2001 + USERS; userId++) {
            final long uid = userId;
            pool.submit(() -> {
                try {
                    mockLogin(uid);
                    ready.countDown();
                    begin.await(); // 卡住，等发令枪，保证100个线程"同一瞬间"打进去
                    Result r = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (r.getSuccess()) {
                        successCount.incrementAndGet();
                    } else if ("库存不足！".equals(r.getErrorMsg())) {
                        stockNotEnough.incrementAndGet();
                    } else if ("请勿重复下单！".equals(r.getErrorMsg())) {
                        duplicate.incrementAndGet();
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    UserHolder.removeUser();
                    end.countDown();
                }
            });
        }

        ready.await();   // 等100人全部就位
        long t0 = System.currentTimeMillis();
        begin.countDown(); // 开抢！
        end.await();
        long cost = System.currentTimeMillis() - t0;
        pool.shutdown();

        String stockLeft = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        Long orderSize = stringRedisTemplate.opsForSet().size(ORDER_KEY);
        System.out.println("========== 防超卖测试：" + USERS + "人抢" + STOCK + "张 ==========");
        System.out.println(">>> 成功下单人数 = " + successCount.get());
        System.out.println(">>> 库存不足人数 = " + stockNotEnough.get());
        System.out.println(">>> 重复下单人数 = " + duplicate.get());
        System.out.println(">>> 异常数量     = " + error.get());
        System.out.println(">>> Redis剩余库存 = " + stockLeft + "，订单集合人数 = " + orderSize);
        System.out.println(">>> 100个并发请求总耗时 = " + cost + "ms");

        assertEquals(STOCK, successCount.get(), "成功人数必须恰好等于库存，多一张都是超卖！");
        assertEquals(USERS - STOCK, stockNotEnough.get(), "其余人应全部收到库存不足");
        assertEquals(0, duplicate.get(), "不同用户之间不该出现重复下单");
        assertEquals(0, error.get(), "不允许有任何异常");
        assertEquals("0", stockLeft);
        assertEquals((long) STOCK, orderSize);
    }

    /**
     * 测试3：一人一单——同一个用户用30个线程同时点抢购
     * 期望：只有1个成功，其余29个全部被"请勿重复下单"拦住
     */
    @Test
    void testOnePersonOneOrder() throws InterruptedException {
        final int THREADS = 30;
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "100"); // 库存给够，专测一人一单

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch begin = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    mockLogin(3001L); // 注意：30个线程全是同一个用户3001
                    ready.countDown();
                    begin.await();
                    Result r = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (r.getSuccess()) {
                        successCount.incrementAndGet();
                    } else if ("请勿重复下单！".equals(r.getErrorMsg())) {
                        duplicate.incrementAndGet();
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    UserHolder.removeUser();
                    end.countDown();
                }
            });
        }

        ready.await();
        begin.countDown();
        end.await();
        pool.shutdown();

        Long orderSize = stringRedisTemplate.opsForSet().size(ORDER_KEY);
        System.out.println("========== 一人一单测试：同一用户并发" + THREADS + "次 ==========");
        System.out.println(">>> 成功次数 = " + successCount.get());
        System.out.println(">>> 被'重复下单'拦截次数 = " + duplicate.get());
        System.out.println(">>> 异常数量 = " + error.get());
        System.out.println(">>> 订单集合人数 = " + orderSize);

        assertEquals(1, successCount.get(), "同一个用户只能下一单！");
        assertEquals(THREADS - 1, duplicate.get(), "其余请求必须全部被拦");
        assertEquals(0, error.get(), "不允许有任何异常");
        assertEquals(1L, orderSize);
    }
}
