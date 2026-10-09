package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存的包装对象：把业务数据与"逻辑过期时间"一起序列化存入 Redis。
 * 与普通 TTL 不同，它不设置 Redis 自带过期时间，而是靠 expireTime 字段判断是否过期，
 * 过期后仍可先返回旧数据、再异步重建，从而保证接口高可用。
 */
@Data
public class RedisData {
    // 逻辑过期时间
    private LocalDateTime expireTime;
    // 真正的业务数据（如 Shop），反序列化时需按实际类型二次转换
    private Object data;
}
