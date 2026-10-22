package com.shop.rag.search;

import com.shop.common.MyBatisPlusTestSupport;
import com.shop.mapper.SkuMapper;
import com.shop.model.entity.SkuDO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SkuPriceResolver 测试（纯 Mockito，不依赖 MySQL，CI 可跑）
 * <p>
 * 重点锁定三个方法**语义不同**这一点：{@code resolve} 要求价格非空才收录，
 * 而 {@code resolveStock} / {@code resolveSnapshot} 只要查到 SKU 就收录 ——
 * 后者若沿用前者的过滤条件，「有 SKU 但无价格」的商品会被当成「无 SKU」放行，售罄判定失效。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkuPriceResolver - SKU 维度聚合")
class SkuPriceResolverTest {

    @Mock
    private SkuMapper skuMapper;

    private SkuPriceResolver resolver;

    @BeforeAll
    static void initTableInfo() {
        MyBatisPlusTestSupport.initTableInfo(SkuDO.class);
    }

    @BeforeEach
    void setUp() {
        resolver = new SkuPriceResolver(skuMapper);
    }

    private static SkuDO sku(long productId, String price, Integer stock) {
        SkuDO sku = new SkuDO();
        sku.setProductId(productId);
        sku.setPrice(price == null ? null : new BigDecimal(price));
        sku.setStock(stock);
        return sku;
    }

    // ==================== resolve：价格区间 ====================

    @Test
    @DisplayName("resolve：聚合出最低价与最高价")
    void resolveReturnsPriceRange() {
        when(skuMapper.selectList(any())).thenReturn(List.of(
                sku(1L, "30", 5), sku(1L, "10", 5), sku(1L, "20", 5)));

        BigDecimal[] prices = resolver.resolve(List.of(1L)).get(1L);

        assertEquals(new BigDecimal("10"), prices[0]);
        assertEquals(new BigDecimal("30"), prices[1]);
    }

    @Test
    @DisplayName("resolve：价格全为空的商品不收录")
    void resolveExcludesProductWithoutPrice() {
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(1L, null, 5)));

        assertFalse(resolver.resolve(List.of(1L)).containsKey(1L));
    }

    // ==================== resolveStock：售罄判定 ====================

    @Test
    @DisplayName("resolveStock：返回全部 SKU 库存之和")
    void resolveStockSumsAllSkus() {
        when(skuMapper.selectList(any())).thenReturn(List.of(
                sku(1L, "10", 5), sku(1L, "20", 3), sku(1L, "30", 0)));

        assertEquals(8, resolver.resolveStock(List.of(1L)).get(1L).intValue());
    }

    @Test
    @DisplayName("resolveStock：stock 为 null 按 0 计入，且商品仍进 Map")
    void resolveStockTreatsNullAsZero() {
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(1L, "10", null)));

        Map<Long, Integer> stocks = resolver.resolveStock(List.of(1L));

        assertTrue(stocks.containsKey(1L), "查到了 SKU 就应进 Map，否则会被当成「无 SKU」而放行");
        assertEquals(0, stocks.get(1L).intValue());
    }

    @Test
    @DisplayName("resolveStock：查询异常时返回空 Map（契约：永不抛异常）")
    void resolveStockSwallowsException() {
        when(skuMapper.selectList(any())).thenThrow(new RuntimeException("DB 不可用"));

        assertTrue(resolver.resolveStock(List.of(1L)).isEmpty());
    }

    // ==================== resolveSnapshot：价格 + 库存一次取全 ====================

    @Test
    @DisplayName("resolveSnapshot：一次取全价格区间与总库存")
    void snapshotReturnsPriceAndStock() {
        when(skuMapper.selectList(any())).thenReturn(List.of(
                sku(1L, "30", 5), sku(1L, "10", 0), sku(1L, "20", 2)));

        SkuSnapshot snapshot = resolver.resolveSnapshot(List.of(1L)).get(1L);

        assertEquals(new BigDecimal("10"), snapshot.minPrice());
        assertEquals(new BigDecimal("30"), snapshot.maxPrice());
        assertEquals(7, snapshot.totalStock());
        assertFalse(snapshot.outOfStock());
    }

    @Test
    @DisplayName("resolveSnapshot：有 SKU 但无价格时仍收录 —— 与 resolve 的语义差异")
    void snapshotKeepsProductWithoutPrice() {
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(1L, null, 0)));

        Map<Long, SkuSnapshot> snapshots = resolver.resolveSnapshot(List.of(1L));

        assertTrue(snapshots.containsKey(1L),
                "有 SKU 就必须进 Map：若沿用 resolve 的「价格非空才收录」，该商品会被漏判为无 SKU 而放行");
        assertNull(snapshots.get(1L).minPrice());
        assertTrue(snapshots.get(1L).outOfStock(), "总库存 0 应判为售罄");
    }

    @Test
    @DisplayName("resolveSnapshot：无 SKU 的商品不在 Map 中（调用方据此放行）")
    void snapshotExcludesProductWithoutSku() {
        when(skuMapper.selectList(any())).thenReturn(List.of());

        assertTrue(resolver.resolveSnapshot(List.of(999L)).isEmpty());
    }

    @Test
    @DisplayName("resolveSnapshot：查询异常时返回空 Map（契约：永不抛异常）")
    void snapshotSwallowsException() {
        when(skuMapper.selectList(any())).thenThrow(new RuntimeException("DB 不可用"));

        assertTrue(resolver.resolveSnapshot(List.of(1L)).isEmpty());
    }

    @Test
    @DisplayName("空入参：不发查询，直接返回空 Map")
    void emptyInputReturnsEmptyMap() {
        assertTrue(resolver.resolve(List.of()).isEmpty());
        assertTrue(resolver.resolveStock(List.of()).isEmpty());
        assertTrue(resolver.resolveSnapshot(List.of()).isEmpty());
    }
}
