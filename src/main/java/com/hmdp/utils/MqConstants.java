package com.hmdp.utils;

/**
 * MQ 常量：交换机、队列、路由键统一管理
 */
public class MqConstants {
    /** 订单交换机（Direct） */
    public static final String ORDER_EXCHANGE = "order.exchange";
    /** 订单队列 */
    public static final String ORDER_QUEUE = "order.queue";
    /** 订单路由键 */
    public static final String ORDER_ROUTING_KEY = "order.create";

    // ==================== 死信链路（DLX = Dead Letter Exchange）====================

    /** 死信交换机：订单消息消费失败后，会被重新发布到这里 */
    public static final String ORDER_DLX_EXCHANGE = "order.dlx.exchange";
    /** 死信队列：失败消息的最终归宿，方便追溯和补偿 */
    public static final String ORDER_DLX_QUEUE = "order.dlx.queue";
    /** 死信路由键：死信交换机靠它把消息路由到死信队列 */
    public static final String ORDER_DLX_ROUTING_KEY = "order.dead";
}