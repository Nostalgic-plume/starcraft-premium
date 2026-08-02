package com.shop.mq;

import com.shop.common.BusinessException;
import com.shop.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 异步落单消费端
 * <p>
 * 接收 OrderProducer 投递的异步落单消息，在事务内完成 Redis 预扣 + MySQL 行锁落单。
 * 消费端处理成功 → 更新轮询状态为订单ID + 发送延迟取消消息；
 * 业务异常（库存不足等）→ 标记 FAILED，不触发 MQ 重试；
 * 系统异常 → 删除幂等键，抛出异常触发 MQ 重试。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-create-topic",
        consumerGroup = "order-create-consumer-group"
)
public class OrderCreateConsumer implements RocketMQListener<OrderCreateMessage> {

    private final OrderService orderService;
    private final RedisTemplate<String, String> redisTemplate;
    private final OrderProducer orderProducer;

    /**
     * 幂等键前缀：order:dedup:{orderToken}
     * 消费端 SETNX 去重，防止 RocketMQ 至少一次投递导致重复落单
     */
    private static final String DEDUP_KEY_PREFIX = "order:dedup:";

    /**
     * 处理状态前缀：order:process:{processingId}
     * 值含义：PROCESSING（处理中）| 订单ID（成功）| FAILED:原因（失败）
     */
    private static final String PROCESS_KEY_PREFIX = "order:process:";

    @Override
    public void onMessage(OrderCreateMessage msg) {
        String orderToken = msg.getOrderToken();

        // ① 幂等校验——防止 RocketMQ 重复投递
        String dedupKey = DEDUP_KEY_PREFIX + orderToken;
        Boolean firstTime = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 1, TimeUnit.DAYS);
        if (Boolean.FALSE.equals(firstTime)) {
            log.info("重复消息跳过: orderToken={}", orderToken);
            return;
        }

        try {
            // ② 事务内落单（Redis 预扣 + MySQL 行锁 + 订单创建）
            Long orderId = orderService.processOrder(msg);

            // ③ 更新轮询状态为成功
            redisTemplate.opsForValue().set(
                    PROCESS_KEY_PREFIX + orderToken,
                    String.valueOf(orderId),
                    7, TimeUnit.DAYS);

            // ④ 发送延迟取消消息（30 分钟后检查支付状态）
            orderProducer.sendDelayCancel(orderId);

            log.info("异步落单处理完成: orderId={}, orderToken={}", orderId, orderToken);

        } catch (BusinessException e) {
            // 业务异常（库存不足、商品已下架等）：标记失败，不重试
            log.warn("异步落单业务失败: orderToken={}, reason={}", orderToken, e.getMessage());
            redisTemplate.opsForValue().set(
                    PROCESS_KEY_PREFIX + orderToken,
                    "FAILED:" + e.getMessage(),
                    1, TimeUnit.DAYS);
            // 保留幂等键——防止后续重复消息重新处理同样的业务错误

        } catch (Exception e) {
            // 系统异常（DB 断开、序列化错误等）：清理状态，让 MQ 重试
            log.error("异步落单系统异常: orderToken={}", orderToken, e);
            redisTemplate.delete(PROCESS_KEY_PREFIX + orderToken);
            redisTemplate.delete(dedupKey);
            throw e;
        }
    }
}
