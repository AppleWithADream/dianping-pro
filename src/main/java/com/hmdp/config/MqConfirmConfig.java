package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * 生产端确认：给 Spring Boot 自动配置的 RabbitTemplate
 *
 */
@Slf4j
@Component
public class MqConfirmConfig implements InitializingBean {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public void afterPropertiesSet() {
        // 消息路由不到任何队列时，触发 ReturnCallback（不静默丢弃）
        rabbitTemplate.setMandatory(true);

        // ---------- 生产端确认回调：broker 收到消息后异步回调 ----------
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String id = (correlationData != null) ? correlationData.getId() : "无";
            if (ack) {
                log.info("消息已确认送达交换机：id={}", id);
            } else {
                log.error("消息确认失败：id={}, 原因={}", id, cause);
            }
        });

        // ---------- 不可路由回调（spring-rabbit 2.2.x 的 5 参数签名）----------
        // 顺序：message, replyCode, replyText, exchange, routingKey
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) ->
                log.error("消息无法路由到队列：exchange={}, routingKey={}, replyCode={}, replyText={}, body={}",
                        exchange, routingKey, replyCode, replyText,
                        new String(message.getBody(), StandardCharsets.UTF_8)));

        log.info("生产端确认回调已挂载：confirm + return（mandatory=true）");
    }
}
