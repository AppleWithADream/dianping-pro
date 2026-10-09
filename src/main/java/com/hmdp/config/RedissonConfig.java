package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类：创建 RedissonClient 并交给 Spring 容器管理。
 * Redisson 提供可重入锁、看门狗自动续期等能力，用于秒杀一人一单的加锁。
 */
@Configuration
public class RedissonConfig {

    // 装配 RedissonClient 客户端（单机模式）
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 单机节点地址与密码（教学项目直接写死，生产应走配置文件）
        config.useSingleServer().setAddress("redis://192.168.100.128:6379").setPassword("123321");
        return Redisson.create(config);
    }
}
