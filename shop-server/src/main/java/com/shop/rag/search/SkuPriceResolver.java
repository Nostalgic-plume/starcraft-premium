package com.shop.rag.search;

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
 * SKU 维度聚合解析器（商品价格区间与库存的唯一来源）
 * <p>
 * 原先价格聚合逻辑内嵌在 {@link ProductIndexer} 中，仅服务于向量索引构建；关键词路补齐分类/价格过滤后
 * 同样需要「商品 → 价格区间」，故提取为共享组件，避免两处各写一份聚合逻辑而漂移。
 * 2026-10 检索链路补齐库存过滤后新增 {@link #resolveStock}，本类职责由「价格解析」扩展为
 * 「SKU 维度聚合（价格 + 库存）」——两者同源（sku 表），不引入额外数据源。
 * <p>
 * 语义：价格返回商品全部 SKU 的最低与最高价；库存返回全部 SKU 的库存之和。
 * 无 SKU 或价格全为空的商品不出现在返回 Map 中，
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

    /**
     * 批量解析商品总库存（售罄判定用）
     * <p>
     * 语义：返回商品全部 SKU 的库存之和，即「是否可售」的判定依据（{@code <= 0} 视为售罄）。
     * {@code stock} 为 {@code null} 的 SKU 按 0 计入——不跳过，否则该商品会因查不到而落入
     * 「不在 Map 中」的分支被放行，与「明确无货」的语义相悖。
     * <p>
     * ⚠️ 无 SKU 的商品**不出现在返回 Map 中**。调用方必须区分两种缺失：
     * 「不在 Map 中」= 数据缺失（无 SKU），按「缺失即放行」处理；
     * 「在 Map 中且 {@code <= 0}」= 明确售罄，剔除。
     * 不可用 {@code getOrDefault(id, 0)} 一刀切——那会把无 SKU 商品误判为售罄。
     * <p>
     * 与 {@link #resolve} 的差异：本方法**内部消化查询异常**并返回空 Map。
     * 库存过滤属检索链路的可选增强，任何查询抖动都不应中断检索主流程，
     * 故契约上保证「永不抛异常」，调用方无需额外 try/catch。
     *
     * @param productIds 商品 ID 列表（可为 null / 空）
     * @return productId → 总库存；无 SKU 的商品不在 Map 中；查询异常时返回空 Map（过滤不生效）
     */
    public Map<Long, Integer> resolveStock(List<Long> productIds) {
        if (CollUtil.isEmpty(productIds)) {
            return Map.of();
        }

        try {
            List<SkuDO> skus = skuMapper.selectList(
                    new LambdaQueryWrapper<SkuDO>()
                            .in(SkuDO::getProductId, productIds)
                            .select(SkuDO::getProductId, SkuDO::getStock));
            if (CollUtil.isEmpty(skus)) {
                return Map.of();
            }

            Map<Long, Integer> stockMap = new HashMap<>();
            for (SkuDO sku : skus) {
                if (sku == null || sku.getProductId() == null) {
                    continue;
                }
                int stock = sku.getStock() != null ? sku.getStock() : 0;
                stockMap.merge(sku.getProductId(), stock, Integer::sum);
            }
            return stockMap;
        } catch (Exception e) {
            log.warn("SKU 库存查询异常，本次不做库存过滤: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 批量解析商品快照（价格区间 + 总库存，一次查询取全）
     * <p>
     * 用于「检索结果富化」：既需要价格区间展示，又需要总库存判定可售性，
     * 若分成 {@link #resolve} + {@link #resolveStock} 两次调用会重复查一次 sku 表。
     * <p>
     * ⚠️ 与 {@link #resolve} 的语义差异（易踩）：本方法**只要查到 SKU 就放进 Map**，
     * {@code minPrice}/{@code maxPrice} 允许为 null；而 {@code resolve} 要求价格非空才放入。
     * 售罄判定依赖「查得到的 SKU 才可能售罄」这一语义，若沿用 {@code resolve} 的过滤条件，
     * 「有 SKU 但无价格」的商品会被漏判成「无 SKU」而被放行。
     * <p>
     * 异常处理与 {@link #resolveStock} 一致：内部消化并返回空 Map，调用方无需 try/catch。
     *
     * @param productIds 商品 ID 列表（可为 null / 空）
     * @return productId → 聚合快照；无 SKU 的商品不在 Map 中；查询异常时返回空 Map
     */
    public Map<Long, SkuSnapshot> resolveSnapshot(List<Long> productIds) {
        if (CollUtil.isEmpty(productIds)) {
            return Map.of();
        }

        try {
            List<SkuDO> skus = skuMapper.selectList(
                    new LambdaQueryWrapper<SkuDO>()
                            .in(SkuDO::getProductId, productIds)
                            .select(SkuDO::getProductId, SkuDO::getPrice, SkuDO::getStock));
            if (CollUtil.isEmpty(skus)) {
                return Map.of();
            }

            Map<Long, List<SkuDO>> grouped = skus.stream()
                    .filter(Objects::nonNull)
                    .filter(sku -> sku.getProductId() != null)
                    .collect(Collectors.groupingBy(SkuDO::getProductId));

            Map<Long, SkuSnapshot> snapshotMap = new HashMap<>();
            for (Map.Entry<Long, List<SkuDO>> entry : grouped.entrySet()) {
                List<SkuDO> group = entry.getValue();
                BigDecimal min = group.stream()
                        .map(SkuDO::getPrice)
                        .filter(Objects::nonNull)
                        .min(BigDecimal::compareTo)
                        .orElse(null);
                BigDecimal max = group.stream()
                        .map(SkuDO::getPrice)
                        .filter(Objects::nonNull)
                        .max(BigDecimal::compareTo)
                        .orElse(null);
                int totalStock = group.stream()
                        .map(SkuDO::getStock)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .sum();
                snapshotMap.put(entry.getKey(), new SkuSnapshot(min, max, totalStock));
            }
            return snapshotMap;
        } catch (Exception e) {
            log.warn("SKU 快照查询异常，本次不做价格与库存富化: {}", e.getMessage());
            return Map.of();
        }
    }
}
