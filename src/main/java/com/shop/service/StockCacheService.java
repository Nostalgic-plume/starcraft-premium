package com.shop.service;

import com.shop.constant.ProductConstants;
import com.shop.mapper.SkuMapper;
import com.shop.model.entity.SkuDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 库存缓存服务
 * <p>
 * 基于 Lua 脚本实现原子库存预扣，减少数据库并发压力。
 * 库存以数据库为最终凭证，Redis 作为前置快速校验层。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final SkuMapper skuMapper;

    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT;

    static {
        DEDUCT_SCRIPT = new DefaultRedisScript<>();
        DEDUCT_SCRIPT.setScriptText(
                "local stock = redis.call('GET', KEYS[1])\n" +
                "if not stock then return -1 end\n" +
                "if tonumber(stock) >= tonumber(ARGV[1]) then\n" +
                "  redis.call('DECRBY', KEYS[1], ARGV[1])\n" +
                "  return 1\n" +
                "end\n" +
                "return 0"
        );
        DEDUCT_SCRIPT.setResultType(Long.class);
    }

    /**
     * 初始化库存到 Redis（不存在时才设置，避免覆盖已有值）
     */
    public void initStock(Long skuId, Integer stock) {
        String key = ProductConstants.CACHE_SKU_STOCK + skuId;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(stock));
        if (Boolean.TRUE.equals(ok)) {
            log.debug("SKU 库存已加载到 Redis: skuId={}, stock={}", skuId, stock);
        }
    }

    /**
     * 原子扣库存
     *
     * @param skuId    SKU ID
     * @param quantity 扣减数量
     * @return true=扣减成功 false=库存不足
     * @throws IllegalStateException Redis 异常时抛出，由调用方决定是否降级
     */
    public boolean tryDeduct(Long skuId, Integer quantity) {
        String key = ProductConstants.CACHE_SKU_STOCK + skuId;
        Long result = redisTemplate.execute(DEDUCT_SCRIPT, List.of(key), String.valueOf(quantity));

        if (result == null) {
            throw new IllegalStateException("Redis 执行 Lua 扣库存返回 null");
        }

        // 缓存未命中，从数据库加载后重试
        if (result == -1) {
            SkuDO sku = skuMapper.selectById(skuId);
            if (sku == null) {
                return false;
            }
            initStock(skuId, sku.getStock());
            result = redisTemplate.execute(DEDUCT_SCRIPT, List.of(key), String.valueOf(quantity));
            return result != null && result == 1;
        }

        return result == 1;
    }

    /**
     * 回滚 Redis 库存（取消订单/事务回滚时调用）
     */
    public void restoreStock(Long skuId, Integer quantity) {
        String key = ProductConstants.CACHE_SKU_STOCK + skuId;
        Long result = redisTemplate.opsForValue().increment(key, quantity);
        log.debug("SKU 库存已回滚: skuId={}, quantity={}, currentRedis={}", skuId, quantity, result);
    }
}
