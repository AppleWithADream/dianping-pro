package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 秒杀订单服务实现类：基于 Redis Lua + 阻塞队列的异步秒杀。
 * 流程：Lua 脚本原子地判断库存与一人一单 → 成功则立即返回订单号 →
 *       把下单任务放入内存阻塞队列 → 后台单线程异步扣库存、写订单表。
 * 这样接口只做 Redis 操作，响应极快，数据库压力由异步线程平滑消化。
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 订单阻塞队列：主线程放订单，异步线程取订单，容量约 100 万
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 单线程线程池：串行消费队列，保证订单按入队顺序处理
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //  Bean 初始化完成后立即启动异步消费线程
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 异步订单处理器：不停从队列取单并落库
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.阻塞等待队列中的订单（无订单时线程挂起，不占 CPU）
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单（扣库存 + 写订单表）
                    handleVocherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("获取订单异常", e);
                }
            }
        }
    }

    // 异步线程内通过代理对象调用，保证 @Transactional 事务生效
    private void handleVocherOrder(VoucherOrder voucherOrder) {
        proxy.createVocherOrder(voucherOrder);
    }

    // 事务代理对象，在 seckillVoucher 中通过 AopContext 赋值
    private IVoucherOrderService proxy;

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.获取用户
        Long userId = UserHolder.getUser().getId();
        // 2.执行lua脚本（返回值：0=成功 1=库存不足 2=重复下单）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        int r = result.intValue();
        // 3.判断结果是否为0
        if (r != 0) {
            // 3.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足！" : "请勿重复下单！");
        }
        // 4.为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 4.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 4.2.用户id
        voucherOrder.setUserId(userId);
        // 4.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 4.4.获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.5.放入阻塞队列
        orderTasks.add(voucherOrder);
        // 5.返回订单id
        return Result.ok(orderId);
    }


    /**
     * 创建订单
    * @param voucherOrder
    */
    @Transactional
    public void createVocherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = voucherOrder.getUserId();
        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断是否存在
        if (count > 0) {
            // 用户已经购买过
            throw new RuntimeException("用户已经购买过该优惠券！");
        }
        // 扣减库存（CAS：stock > 0 才更新，防止超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            throw new RuntimeException("库存不足！");
        }
        // 保存订单
        save(voucherOrder);
    }



//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 查询优惠券
//        SeckillVoucher voucher= seckillVoucherService.getById(voucherId);
//        // 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        // 获取当前用户
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁
//        boolean isLocked = lock.tryLock();
//        if (!isLocked) {
//            return Result.fail("请勿重复下单！");
//        }
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVocherOrder(voucherId);
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }

//    @Transactional
//    public Result createVocherOrder(Long voucherId) {
//        // 获取用户
//        Long userId = UserHolder.getUser().getId();
//        // 一人一单：判断是否已经购买过
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0) {
//            return Result.fail("用户已经购买过该优惠券！");
//        }
//        // 扣减库存（CAS：stock > 0 才更新，防止超卖）
//        boolean success = seckillVoucherService.update()
//                // 减库存
//                .setSql("stock = stock - 1")
//                // 条件
//                .eq("voucher_id", voucherId).gt("stock", 0)
//                .update();
//        if (!success) {
//            return Result.fail("库存不足！");
//        }
//        // 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 生成全局唯一订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        // 保存订单
//        save(voucherOrder);
//
//        return Result.ok(orderId);
//    }
}
