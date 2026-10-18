package com.hmdp;

import com.hmdp.utils.MqConstants;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 生产端确认的测试数据准备和验证。
 */
@SpringBootTest
public class MqConfirmTestDataSupportTest {

    private static final Long VOUCHER_ID = 10L;
    private static final String SINGLE_TOKEN = "confirmtest1";
    private static final Long SINGLE_USER_ID = 1001L;
    private static final int STOCK = 100;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DataSource dataSource;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 第 1 轮准备：清订单、库存 100、造单用户登录态 */
    @Test
    public void prepareSingleUser() {
        int deleted = executeUpdate("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        executeUpdate("UPDATE tb_seckill_voucher SET stock = ? WHERE voucher_id = ?", STOCK, VOUCHER_ID);
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, String.valueOf(STOCK));
        stringRedisTemplate.delete("seckill:order:" + VOUCHER_ID);

        String tokenKey = "login:token:" + SINGLE_TOKEN;
        stringRedisTemplate.opsForHash().put(tokenKey, "id", String.valueOf(SINGLE_USER_ID));
        stringRedisTemplate.opsForHash().put(tokenKey, "nickName", "confirmTester");
        stringRedisTemplate.opsForHash().put(tokenKey, "icon", "");
        stringRedisTemplate.expire(tokenKey, java.time.Duration.ofHours(2));

        System.out.println("准备完成：删订单 " + deleted + " 条，库存 " + STOCK + "，token=" + SINGLE_TOKEN);
    }

    /** 查看当前状态 */
    @Test
    public void queryState() {
        String redisStock = stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID);
        Long orderedUsers = stringRedisTemplate.opsForSet().size("seckill:order:" + VOUCHER_ID);
        System.out.println("订单数 = " + queryInt("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID));
        System.out.println("MySQL 库存 = " + queryInt("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID));
        System.out.println("Redis 库存 = " + redisStock);
        System.out.println("已购集合大小 = " + orderedUsers);
    }

    /**
     * 发一条路由键错误的消息，验证 ReturnCallback。
     * 注意不能改 MqConstants.ORDER_ROUTING_KEY，那个常量绑定和发送都用，改了两边一起变就测不出来了。
     */
    @Test
    public void sendToWrongRoutingKey() throws InterruptedException {
        rabbitTemplate.convertAndSend(
                MqConstants.ORDER_EXCHANGE,
                "order.wrong",
                "test-payload",
                new CorrelationData("test:wrong-route"));
        Thread.sleep(2000);
        System.out.println("已发到 order.exchange，路由键 order.wrong，看控制台的 confirm 和 return 两条日志");
    }

    /** 清场 */
    @Test
    public void resetAll() {
        int deleted = executeUpdate("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        executeUpdate("UPDATE tb_seckill_voucher SET stock = 100 WHERE voucher_id = ?", VOUCHER_ID);
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "100");
        stringRedisTemplate.delete("seckill:order:" + VOUCHER_ID);
        stringRedisTemplate.delete("login:token:" + SINGLE_TOKEN);
        System.out.println("清场完成：删订单 " + deleted + " 条");
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
