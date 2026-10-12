package com.shop.rag.search;

import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KeywordSearchService 分类 / 价格过滤单元测试（纯 Mockito，不依赖 MySQL / Qdrant）
 * <p>
 * 回归缺陷：关键词路此前完全不过滤 categoryId / minPrice / maxPrice，
 * 与向量路的元数据过滤语义不一致——带筛选的请求会返回越界商品。
 * 本测试锁定「与向量路同语义」：分类比对 firstCategoryId，价格用 SKU 区间，字段缺失一律放行。
 * <p>
 * ⚠️ FULLTEXT 查询只回填 {@code id} 与 {@code first_category_id}（展示数据改由回查提供），
 * 故 {@code firstCategoryId} 必须仍在 SELECT 列表内 —— 否则分类过滤会静默失效。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeywordSearchService - 关键词路过滤补齐")
class KeywordSearchServiceTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private SkuPriceResolver skuPriceResolver;

    private KeywordSearchService keywordSearchService;

    @BeforeEach
    void setUp() {
        keywordSearchService = new KeywordSearchService(productMapper, skuPriceResolver);
    }

    /** 模拟 FULLTEXT 查询的返回：只带 id 与 firstCategoryId 两个字段 */
    private static ProductDO product(long id, Long firstCategoryId) {
        ProductDO product = new ProductDO();
        product.setId(id);
        product.setFirstCategoryId(firstCategoryId);
        return product;
    }

    private static SkuSnapshot snapshot(String minPrice, String maxPrice) {
        return new SkuSnapshot(new BigDecimal(minPrice), new BigDecimal(maxPrice), 10);
    }

    private static List<Long> ids(List<RagCandidate> candidates) {
        return candidates.stream().map(RagCandidate::productId).toList();
    }

    @Test
    @DisplayName("分类过滤：firstCategoryId 不匹配的商品被剔除")
    void shouldFilterByCategory() {
        when(productMapper.searchByKeyword(anyString(), anyInt()))
                .thenReturn(List.of(product(1L, 5L), product(2L, 6L)));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        List<RagCandidate> candidates = keywordSearchService.searchByKeyword("手机", 10, 5L, null, null);

        assertEquals(List.of(1L), ids(candidates));
    }

    @Test
    @DisplayName("分类字段缺失时放行（与向量路 payload 缺字段的宽松语义一致）")
    void shouldPassThroughWhenCategoryMissing() {
        when(productMapper.searchByKeyword(anyString(), anyInt()))
                .thenReturn(List.of(product(1L, null)));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        List<RagCandidate> candidates = keywordSearchService.searchByKeyword("手机", 10, 5L, null, null);

        assertEquals(List.of(1L), ids(candidates));
    }

    @Test
    @DisplayName("价格过滤：区间完全落在筛选条件外的商品被剔除")
    void shouldFilterByPrice() {
        when(productMapper.searchByKeyword(anyString(), anyInt()))
                .thenReturn(List.of(product(1L, null), product(2L, null), product(3L, null)));

        Map<Long, SkuSnapshot> snapshotMap = new HashMap<>();
        snapshotMap.put(1L, snapshot("50", "80"));     // 最高价 < 100 → 剔除
        snapshotMap.put(2L, snapshot("120", "200"));   // 与 [100,250] 有交集 → 命中
        snapshotMap.put(3L, snapshot("300", "400"));   // 最低价 > 250 → 剔除
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(snapshotMap);

        List<RagCandidate> candidates = keywordSearchService.searchByKeyword(
                "手机", 10, null, new BigDecimal("100"), new BigDecimal("250"));

        assertEquals(List.of(2L), ids(candidates));
    }

    @Test
    @DisplayName("取数放大一倍：SQL LIMIT 为请求条数的 2 倍，抵消内存过滤造成的候选不足")
    void shouldOverFetchBeforeFiltering() {
        when(productMapper.searchByKeyword(anyString(), anyInt())).thenReturn(List.of());

        keywordSearchService.searchByKeyword("手机", 50, null, null, null);

        verify(productMapper).searchByKeyword("手机", 100);
    }

    @Test
    @DisplayName("SKU 快照不可用时退化为不过滤（放行全部候选），不丢结果")
    void shouldNotFilterWhenSnapshotUnavailable() {
        when(productMapper.searchByKeyword(anyString(), anyInt()))
                .thenReturn(List.of(product(1L, null)));
        // resolveSnapshot 内部消化查询异常并返回空 Map，此处即模拟该结果
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        List<RagCandidate> candidates = keywordSearchService.searchByKeyword(
                "手机", 10, null, new BigDecimal("999"), null);

        assertEquals(List.of(1L), ids(candidates), "快照缺失应放行候选，避免整条检索结果被清空");
    }

    @Test
    @DisplayName("FULLTEXT 异常时返回空列表，由 RRF 退化为纯向量路")
    void shouldReturnEmptyOnSqlFailure() {
        when(productMapper.searchByKeyword(anyString(), anyInt()))
                .thenThrow(new RuntimeException("FULLTEXT 异常"));

        List<RagCandidate> candidates = keywordSearchService.searchByKeyword("手机", 10, null, null, null);

        assertTrue(candidates.isEmpty());
    }

    @Test
    @DisplayName("limit <= 0 时直接返回空列表，不发起查询")
    void shouldShortCircuitOnIllegalLimit() {
        assertTrue(keywordSearchService.searchByKeyword("手机", 0, null, null, null).isEmpty());
        assertTrue(keywordSearchService.searchByKeyword("  ", 10, null, null, null).isEmpty());
    }
}
