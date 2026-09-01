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
import com.shop.service.StockCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

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
    private final StockCacheService stockCacheService;

    /**
     * 每 60 分钟执行一次（兜底），自动取消超时未支付订单。
     * <p>
     * 主取消机制已迁移到 RocketMQ 延迟消息（30 分钟），
     * 此处作为 MQ 不可用时的安全兜底。
     * </p>
     */
    @Scheduled(fixedRate = 3_600_000) // 60 分钟
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
                    stockCacheService.restoreStock(item.getSkuId(), item.getQuantity());
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

    /**
     * 每 10 分钟执行一次（兜底）：扫描接口预扣后长时间未落单/未清理的预扣记录（order:deduct:*），
     * 视为悬空预扣（MQ 消息丢失 / 服务在投递前宕机），恢复其对应 Redis 库存并删除记录。
     * <p>
     * 正常流程中预扣记录在消费端落单成功或业务失败时即被删除，留存超过 {@code DEDUCT_TIMEOUT_MINUTES}
     * 的即为悬空；恢复的是 Redis 库存（接口预扣只改了 Redis，DB 尚未扣减），保证缓存库存可自愈。
     * </p>
     */
    @Scheduled(fixedRate = 600_000) // 10 分钟
    public void recoverTimeoutDeducts() {
        Set<String> keys = redisTemplate.keys(OrderConstants.CACHE_ORDER_DEDUCT + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long timeout = OrderConstants.DEDUCT_TIMEOUT_MINUTES * 60_000L;
        int recovered = 0;
        for (String key : keys) {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                String[] parts = value.split(",");
                long timestamp = Long.parseLong(parts[0]);
                if (now - timestamp < timeout) {
                    continue; // 未超时，可能仍在消费中，跳过
                }
                // 恢复悬空预扣的库存并删除记录
                for (int i = 1; i < parts.length; i++) {
                    String[] kv = parts[i].split(":");
                    if (kv.length != 2) {
                        continue;
                    }
                    stockCacheService.restoreStock(Long.parseLong(kv[0]), Integer.parseInt(kv[1]));
                }
                redisTemplate.delete(key);
                recovered++;
                log.warn("恢复悬空预扣库存: key={}, 涉及 {} 个 SKU", key, parts.length - 1);
            } catch (NumberFormatException e) {
                log.error("预扣记录解析失败，请人工处理: key={}, value={}", key, value);
            }
        }
        if (recovered > 0) {
            log.info("悬空预扣恢复完成: 本次处理 {} 笔", recovered);
        }
    }
}
