package com.shop.rag.search;

import java.math.BigDecimal;

/**
 * SKU 维度聚合快照：价格区间 + 总库存
 * <p>
 * 一次 sku 表查询即可同时满足「展示价格」与「判定可售」两个用途，
 * 免去分成两次查询。由 {@link SkuPriceResolver#resolveSnapshot} 产出。
 * <p>
 * {@code minPrice} / {@code maxPrice} 允许为 {@code null}（有 SKU 但未定价的商品）；
 * {@code totalStock} 为各 SKU 库存之和，{@code stock} 为 null 的按 0 计入。
 *
 * @param minPrice   最低价（可为 null）
 * @param maxPrice   最高价（可为 null）
 * @param totalStock 全部 SKU 的库存之和
 * @author shop
 */
public record SkuSnapshot(BigDecimal minPrice, BigDecimal maxPrice, int totalStock) {

    /** 是否售罄（总库存 &lt;= 0） */
    public boolean outOfStock() {
        return totalStock <= 0;
    }
}
