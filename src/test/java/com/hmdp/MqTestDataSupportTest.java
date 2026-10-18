package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀压测的数据准备、验证、清场。只动数据，不调接口。
 */
@SpringBootTest
public class MqTestDataSupportTest {

    /** 秒杀券 id（type=1 的那张） */
    private static final Long VOUCHER_ID = 10L;

    /** 压测用户范围，token 名 bench2001 ~ bench4000 */
    private static final int BENCH_USER_START = 2001;
    private static final int BENCH_USER_END = 4000;

    private static final int STOCK_ROUND1 = 300;
    private static final int STOCK_ROUND2 = 300;
    private static final int STOCK_ROUND3 = 2000;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DataSource dataSource;

    /** 造压测用户登录态（管道批量写，2000 个几十毫秒） */
    @Test
    public void prepareBenchUsers() {
        int total = BENCH_USER_END - BENCH_USER_START + 1;
        long begin = System.currentTimeMillis();
        stringRedisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                for (int i = BENCH_USER_START; i <= BENCH_USER_END; i++) {
                    byte[] key = ("login:token:bench" + i).getBytes(StandardCharsets.UTF_8);
                    Map<byte[], byte[]> hash = new HashMap<>(4);
                    hash.put(bytes("id"), bytes(String.valueOf(i)));
                    hash.put(bytes("nickName"), bytes("bench" + i));
                    hash.put(bytes("icon"), new byte[0]);
                    connection.hMSet(key, hash);
                }
                return null;
            }
        });
        System.out.println("造 " + total + " 个压测用户，耗时 " + (System.currentTimeMillis() - begin) + " ms");
    }

    /** 第一轮：一人一单，库存 300 */
    @Test
    public void prepareRound1() {
        prepareRound("第一轮", STOCK_ROUND1);
    }

    /** 第二轮：防超卖，库存 300 */
    @Test
    public void prepareRound2() {
        prepareRound("第二轮", STOCK_ROUND2);
    }

    /** 第三轮：吞吐，库存 2000 */
    @Test
    public void prepareRound3() {
        prepareRound("第三轮", STOCK_ROUND3);
    }

    /** 清订单 + 设库存（MySQL 和 Redis 一起设，两边必须一致） */
    private void prepareRound(String roundName, int stock) {
        int deleted = executeUpdate("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        executeUpdate("UPDATE tb_seckill_voucher SET stock = ? WHERE voucher_id = ?", stock, VOUCHER_ID);
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(stock));
        stringRedisTemplate.delete("seckill:order:" + VOUCHER_ID);
        System.out.println(roundName + " 准备完成：删订单 " + deleted + " 条，库存 " + stock);
    }

    /** 验证：订单数 + 剩余库存 = 初始库存，且没有重复单 */
    @Test
    public void verify() {
        int orderCount = queryInt("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        int dupCount = queryInt("SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order "
                + "WHERE voucher_id = ? GROUP BY user_id HAVING COUNT(*) > 1) t", VOUCHER_ID);
        int mysqlStock = queryInt("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        String redisStock = stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID);
        Long orderedUsers = stringRedisTemplate.opsForSet().size("seckill:order:" + VOUCHER_ID);

        System.out.println("订单数 = " + orderCount);
        System.out.println("MySQL 库存 = " + mysqlStock + "，Redis 库存 = " + redisStock);
        System.out.println("已购集合大小 = " + orderedUsers);
        System.out.println("重复单 = " + dupCount + " 行");
        System.out.println("订单数 + Redis库存 = " + (orderCount + (redisStock == null ? 0 : Integer.parseInt(redisStock))));
    }

    /** 清场：删订单、恢复库存、删压测用户 */
    @Test
    public void resetAll() {
        int deleted = executeUpdate("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        executeUpdate("UPDATE tb_seckill_voucher SET stock = 100 WHERE voucher_id = ?", VOUCHER_ID);
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "100");
        stringRedisTemplate.delete("seckill:order:" + VOUCHER_ID);
        stringRedisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                for (int i = BENCH_USER_START; i <= BENCH_USER_END; i++) {
                    connection.del(("login:token:bench" + i).getBytes(StandardCharsets.UTF_8));
                }
                return null;
            }
        });
        System.out.println("清场完成：删订单 " + deleted + " 条，库存恢复 100，压测用户已删");
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private int executeUpdate(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("SQL 失败: " + e.getMessage());
            return -1;
        }
    }

    private int queryInt(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (Exception e) {
            System.err.println("查询失败: " + e.getMessage());
            return -1;
        }
    }
}
