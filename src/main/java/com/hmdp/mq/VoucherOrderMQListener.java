package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MqConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

/**
 * 秒杀订单消费者：收到 MQ 消息后异步创建订单
 * 开关：application.yaml 加 mq.consumer.enabled: false 可让消费者下线（验证用）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class VoucherOrderMQListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = MqConstants.ORDER_QUEUE)
    public void listen(VoucherOrder voucherOrder, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到秒杀下单消息：orderId={}, userId={}, voucherId={}",
                voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
        try {
            // 跨类调用 service，走 Spring 代理，@Transactional 生效
            voucherOrderService.createVocherOrder(voucherOrder);
            // 业务成功 → 手动 ACK
            channel.basicAck(deliveryTag, false);
            log.info("订单落库成功，已 ACK：orderId={}", voucherOrder.getId());
        } catch (Exception e) {
            log.error("订单落库失败，消息将进入死信队列：orderId={}", voucherOrder.getId(), e);
            channel.basicReject(deliveryTag, false);
        }
    }
}