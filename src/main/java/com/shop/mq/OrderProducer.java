package com.shop.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 消息发送
 * <p>
 * 负责发送异步落单消息和延迟取消消息。
 * 消息发送失败由调用方处理补偿（回滚 Redis 库存等）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProducer {

    private final RocketMQTemplate rocketMQTemplate;

    /** RocketMQ 预设延迟等级 16 = 30 分钟（1s 5s 10s 30s 1m 2m … 20m 30m 1h 2h） */
    public static final int DELAY_LEVEL_30M = 16;

    /**
     * 发送异步落单消息（普通消息，无序）
     */
    public void sendOrderCreate(OrderCreateMessage message) {
        rocketMQTemplate.send("order-create-topic", MessageBuilder.withPayload(message).build());
        log.info("异步落单消息已发送: orderToken={}", message.getOrderToken());
    }

    /**
     * 发送延迟取消消息（30 分钟后检查订单支付状态）
     */
    public void sendDelayCancel(Long orderId) {
        rocketMQTemplate.syncSend(
                "order-delay-cancel-topic",
                MessageBuilder.withPayload(String.valueOf(orderId)).build(),
                3000,
                DELAY_LEVEL_30M
        );
        log.info("延迟取消消息已发送: orderId={}", orderId);
    }
}
