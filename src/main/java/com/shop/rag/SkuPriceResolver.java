package com.shop.rag;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.entity.SkuDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SKU 价格解析器（商品维度价格区间的唯一来源）
 * <p>
 * 原先该逻辑内嵌在 {@link ProductIndexer} 中，仅服务于向量索引构建；关键词路补齐分类/价格过滤后
 * 同样需要「商品 → 价格区间」，故提取为共享组件，避免两处各写一份聚合逻辑而漂移。
 * <p>
 * 语义：返回商品全部 SKU 的最低与最高价。无 SKU 或价格全为空的商品不出现在返回 Map 中，
 * 由调用方按「字段缺失即放行」处理（与向量路 payload 缺字段时的宽松语义一致）。
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkuPriceResolver {

    private final SkuMapper skuMapper;

    /**
     * 批量解析商品价格区间
     *
     * @param productIds 商品 ID 列表（可为 null / 空）
     * @return productId → [最低价, 最高价]；无 SKU 的商品不在 Map 中。查询异常时返回空 Map
     */
    public Map<Long, BigDecimal[]> resolve(List<Long> productIds) {
        if (CollUtil.isEmpty(productIds)) {
            return Map.of();
        }

        List<SkuDO> skus = skuMapper.selectList(
                new LambdaQueryWrapper<SkuDO>()
                        .in(SkuDO::getProductId, productIds)
                        .select(SkuDO::getProductId, SkuDO::getPrice));
        if (CollUtil.isEmpty(skus)) {
            return Map.of();
        }

        Map<Long, BigDecimal[]> priceMap = new HashMap<>();
        Map<Long, List<SkuDO>> grouped = skus.stream()
                .filter(Objects::nonNull)
                .filter(sku -> sku.getProductId() != null)
                .collect(Collectors.groupingBy(SkuDO::getProductId));

        for (Map.Entry<Long, List<SkuDO>> entry : grouped.entrySet()) {
            BigDecimal min = entry.getValue().stream()
                    .map(SkuDO::getPrice)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(null);
            BigDecimal max = entry.getValue().stream()
                    .map(SkuDO::getPrice)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            if (min != null && max != null) {
                priceMap.put(entry.getKey(), new BigDecimal[]{min, max});
            }
        }
        return priceMap;
    }

    /**
     * 解析单个商品的价格区间
     *
     * @param productId 商品 ID
     * @return [最低价, 最高价]；无 SKU 或查询失败返回 null
     */
    public BigDecimal[] resolveOne(Long productId) {
        if (productId == null) {
            return null;
        }
        return resolve(List.of(productId)).get(productId);
    }
}
