package com.shop.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.constant.OrderConstants;
import com.shop.constant.ProductConstants;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.entity.OrderDO;
import com.shop.model.entity.OrderItemDO;
import com.shop.service.StockCacheService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 延迟取消消费端
 * <p>
 * 接收 OrderProducer 投递的延迟消息（30 分钟后），检查订单是否已支付。
 * 若仍未支付则取消订单并回滚库存，作为 ScheduledTasks 的主取消机制。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-delay-cancel-topic",
        consumerGroup = "order-delay-cancel-consumer-group"
)
public class OrderDelayCancelConsumer implements RocketMQListener<String> {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SkuMapper skuMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final StockCacheService stockCacheService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(String orderIdStr) {
        Long orderId = Long.valueOf(orderIdStr);

        // ① 条件更新：只有待支付订单才能取消（防止重复处理）
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId)
                .eq(OrderDO::getStatus, OrderConstants.STATUS_PENDING_PAY)
                .set(OrderDO::getStatus, OrderConstants.STATUS_CANCELLED));
        if (updated == 0) {
            log.info("延迟取消跳过（订单已支付或已取消）: orderId={}", orderId);
            return;
        }

        // ② 回滚库存（MySQL + Redis）+ 淘汰商品缓存
        List<OrderItemDO> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getOrderId, orderId));
        for (OrderItemDO item : items) {
            skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
            stockCacheService.restoreStock(item.getSkuId(), item.getQuantity());
            redisTemplate.delete(ProductConstants.CACHE_PRODUCT + item.getProductId());
        }

        log.info("延迟消息取消订单成功: orderId={}", orderId);
    }
}
