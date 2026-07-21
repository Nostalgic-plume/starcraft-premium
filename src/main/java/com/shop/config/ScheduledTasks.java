package com.shop.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.constant.OrderConstants;
import com.shop.constant.ProductConstants;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.entity.OrderDO;
import com.shop.model.entity.OrderItemDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务
 * 处理超时未支付订单取消、库存回滚等后台任务
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SkuMapper skuMapper;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 每 5 分钟执行一次，自动取消超时未支付订单（下单超过 30 分钟未支付）
     */
    @Scheduled(fixedRate = 300_000) // 5 分钟
    @Transactional(rollbackFor = Exception.class)
    public void cancelTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(OrderConstants.PAY_TIMEOUT_MINUTES);

        List<OrderDO> timeoutOrders = orderMapper.selectList(
                new LambdaQueryWrapper<OrderDO>()
                        .eq(OrderDO::getStatus, OrderConstants.STATUS_PENDING_PAY)
                        .le(OrderDO::getCreateTime, deadline)
                        .eq(OrderDO::getDeleted, 0));

        if (timeoutOrders.isEmpty()) {
            return;
        }

        int cancelled = 0;
        for (OrderDO order : timeoutOrders) {
            try {
                // ① 条件更新订单状态：只有待支付订单才能取消（防止重复处理导致库存双重回滚）
                int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                        .eq(OrderDO::getId, order.getId())
                        .eq(OrderDO::getStatus, OrderConstants.STATUS_PENDING_PAY)
                        .set(OrderDO::getStatus, OrderConstants.STATUS_CANCELLED));
                if (updated == 0) {
                    log.warn("订单状态已变更, 跳过处理: orderNo={}", order.getOrderNo());
                    continue;
                }

                // ② 回滚库存 + 淘汰商品缓存
                List<OrderItemDO> items = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getOrderId, order.getId()));
                for (OrderItemDO item : items) {
                    skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
                    redisTemplate.delete(ProductConstants.CACHE_PRODUCT + item.getProductId());
                }
                cancelled++;

                log.info("超时取消订单: orderNo={}, createTime={}",
                        order.getOrderNo(), order.getCreateTime());
            } catch (Exception e) {
                log.error("取消订单失败: orderNo={}", order.getOrderNo(), e);
            }
        }

        if (cancelled > 0) {
            log.info("超时取消订单完成: 本次处理 {} 笔", cancelled);
        }
    }
}
