package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局 ID 生成器：基于 Redis 的自增计数器 + 时间戳拼接出分布式唯一 ID。
 * 结构（64 位 long）：高位 = 当前时间戳与起始时间的差值，低位 = 当日自增序列号。
 * 时间戳保证趋势递增，序列号保证同一毫秒内不重复。
 */
@Component
public class RedisIdWorker {

    // 起始时间戳（2022-01-01 00:00:00 UTC），时间戳从它开始累计，减少占位位数
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    // 序列号占用的位数（低位 32 位）
    private static final int COUNT_BITS = 32;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成下一个全局唯一 ID
     * @param keyPrefix 业务前缀（如 order），不同业务分开计数
     */
    public long nextId(String keyPrefix) {
        // 1.计算时间戳部分：当前秒 - 起始秒
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2.获取序列号部分：按 "业务:yyyy:MM:dd" 为 key 自增，按天滚动、便于排查统计
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 3.拼接：时间戳左移 32 位后，与低位序列号做或运算
        return timestamp << COUNT_BITS | count;
    }

}
