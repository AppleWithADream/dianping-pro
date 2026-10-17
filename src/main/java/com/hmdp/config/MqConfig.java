package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 配置：声明持久化队列、Direct 交换机、绑定关系，以及 JSON 消息转换器
 */
@Configuration
public class MqConfig {

    /** 订单队列：durable = 持久化，RabbitMQ 重启后队列不丢 */
    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_QUEUE)
                .deadLetterExchange(MqConstants.ORDER_DLX_EXCHANGE)      // 失败消息去哪
                .deadLetterRoutingKey(MqConstants.ORDER_DLX_ROUTING_KEY) // 带什么路由键
                .build();
    }

    /** Direct 交换机：true = 持久化，false = 不自动删除 */
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(MqConstants.ORDER_EXCHANGE, true, false);
    }

    /** 绑定：队列 <- 交换机，路由键 order.create */
    @Bean
    public Binding orderBinding(Queue orderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue)
                .to(orderExchange)
                .with(MqConstants.ORDER_ROUTING_KEY);
    }

    /**
     * JSON 消息转换器：发 VoucherOrder 对象时自动序列化成 JSON，消费端自动反序列化回对象。
     */
    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
    /** 死信交换机 */
    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange(MqConstants.ORDER_DLX_EXCHANGE, true, false);
    }

    /** 死信队列 */
    @Bean
    public Queue orderDlxQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_DLX_QUEUE).build();
    }

    /** 绑定：死信队列 <- 死信交换机 */
    @Bean
    public Binding orderDlxBinding() {
        return BindingBuilder.bind(orderDlxQueue())
                .to(orderDlxExchange())
                .with(MqConstants.ORDER_DLX_ROUTING_KEY);
    }

}