package com.shop.rag.search;

import com.shop.common.MyBatisPlusTestSupport;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RagHitEnricher 测试（纯 Mockito，不依赖 MySQL，CI 可跑）
 * <p>
 * 锁定三条契约（见 {@code docs/rag-id-only-retrieval-design.md}）：
 * <ol>
 *   <li><b>顺序</b>：输出必须与入参（RRF 排序）一致，批量查询的返回顺序不得影响结果；</li>
 *   <li><b>excerpt 来源</b>：取自商品描述，不再是索引文本 —— 这是本次「只召回 ID」的核心收益；</li>
 *   <li><b>只回查 topK</b>：入参条数即回查条数，候选池（50~200 条）不得泄漏进来。</li>
 * </ol>
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagHitEnricher - 检索结果富化")
class RagHitEnricherTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private SkuPriceResolver skuPriceResolver;

    private RagHitEnricher enricher;

    @BeforeAll
    static void initTableInfo() {
        MyBatisPlusTestSupport.initTableInfo(ProductDO.class);
    }

    @BeforeEach
    void setUp() {
        enricher = new RagHitEnricher(productMapper, skuPriceResolver);
    }

    private static ProductDO product(long id, String name, String description) {
        ProductDO product = new ProductDO();
        product.setId(id);
        product.setName(name);
        product.setMainImage("img-" + id);
        product.setDescription(description);
        return product;
    }

    @Test
    @DisplayName("保持入参顺序（RRF 排序），字段完整")
    void keepsInputOrderAndFillsFields() {
        // 数据库返回顺序（1,2,3）刻意与入参顺序（3,1,2）不同
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "商品1", "描述1"),
                product(2L, "商品2", "描述2"),
                product(3L, "商品3", "描述3")));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of(
                1L, new SkuSnapshot(new BigDecimal("10"), new BigDecimal("20"), 5),
                2L, new SkuSnapshot(new BigDecimal("30"), new BigDecimal("40"), 0),
                3L, new SkuSnapshot(new BigDecimal("50"), new BigDecimal("60"), 7)));

        List<RagHitVO> hits = enricher.enrich(List.of(
                new RagCandidate(3L, 0.9), new RagCandidate(1L, 0.8), new RagCandidate(2L, 0.7)));

        assertEquals(List.of(3L, 1L, 2L),
                hits.stream().map(RagHitVO::getProductId).toList(),
                "必须保持 RRF 排序；按查询返回顺序重排会让融合排序白做");
        assertEquals("商品3", hits.get(0).getName());
        assertEquals("img-3", hits.get(0).getImage());
        assertEquals(new BigDecimal("50"), hits.get(0).getMinPrice());
        assertEquals(new BigDecimal("60"), hits.get(0).getMaxPrice());
        assertEquals(0.9, hits.get(0).getScore(), 0.0001);
    }

    @Test
    @DisplayName("excerpt 取自商品描述，不带索引文本的「名称：/描述：」标签前缀")
    void excerptComesFromDescription() {
        when(productMapper.selectList(any()))
                .thenReturn(List.of(product(1L, "华为 Mate 60 Pro 手机", "搭载麒麟芯片与卫星通话功能")));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        List<RagHitVO> hits = enricher.enrich(List.of(new RagCandidate(1L, 0.9)));

        assertEquals("搭载麒麟芯片与卫星通话功能", hits.get(0).getExcerpt(),
                "excerpt 必须是纯描述；若带『名称：』前缀，客服卡片的 40 字配额会被标签挤掉");
    }

    @Test
    @DisplayName("超长描述被截断并补省略号")
    void excerptIsTruncated() {
        String longDescription = "描".repeat(200);
        when(productMapper.selectList(any()))
                .thenReturn(List.of(product(1L, "商品1", longDescription)));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        List<RagHitVO> hits = enricher.enrich(List.of(new RagCandidate(1L, 0.9)));

        assertEquals(123, hits.get(0).getExcerpt().length(), "120 字 + 省略号");
        assertTrue(hits.get(0).getExcerpt().endsWith("..."));
    }

    @Test
    @DisplayName("商品查不到（已删除/下架）：跳过该条，不返回残缺条目")
    void skipsMissingProduct() {
        when(productMapper.selectList(any())).thenReturn(List.of(product(1L, "商品1", "描述1")));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        List<RagHitVO> hits = enricher.enrich(List.of(
                new RagCandidate(1L, 0.9), new RagCandidate(2L, 0.8)));

        assertEquals(1, hits.size());
        assertEquals(1L, hits.get(0).getProductId());
    }

    @Test
    @DisplayName("商品查询整体异常：返回空列表降级，不展示残缺数据")
    void returnsEmptyWhenProductQueryFails() {
        when(productMapper.selectList(any())).thenThrow(new RuntimeException("DB 不可用"));

        List<RagHitVO> hits = enricher.enrich(List.of(new RagCandidate(1L, 0.9)));

        assertTrue(hits.isEmpty(), "宁可少展示，也不返回「有卡片却无名称图片」的残缺条目");
    }

    @Test
    @DisplayName("SKU 快照不可用：仍返回命中项，仅价格为空")
    void keepsHitWhenSnapshotUnavailable() {
        when(productMapper.selectList(any())).thenReturn(List.of(product(1L, "商品1", "描述1")));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        List<RagHitVO> hits = enricher.enrich(List.of(new RagCandidate(1L, 0.9)));

        assertEquals(1, hits.size(), "价格非必需展示项，快照缺失不应导致整条丢失");
        assertNull(hits.get(0).getMinPrice());
        assertNull(hits.get(0).getMaxPrice());
    }

    @Test
    @DisplayName("只回查入参条数：候选池大小不得泄漏到回查")
    void onlyQueriesGivenCandidates() {
        when(productMapper.selectList(any())).thenReturn(List.of(product(1L, "商品1", "描述1")));
        when(skuPriceResolver.resolveSnapshot(anyList())).thenReturn(Map.of());

        enricher.enrich(List.of(new RagCandidate(1L, 0.9), new RagCandidate(1L, 0.8)));

        // 相同 ID 去重后只查一次；若哪天有人把候选池（50~200 条）直接丢进来，此断言会失败
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(skuPriceResolver).resolveSnapshot(captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    @DisplayName("空入参：不触发任何查询")
    void emptyInputSkipsQueries() {
        assertTrue(enricher.enrich(List.of()).isEmpty());
        verifyNoInteractions(productMapper, skuPriceResolver);
    }

    // ========== findDisplayableIds（候选池清洗：RRF 合并前的可展示性判定） ==========

    @Test
    @DisplayName("可展示查询：只返回查到的上架商品 ID，补集由调用方取")
    void findDisplayableIdsReturnsOnlyOnShelfIds() {
        when(productMapper.selectList(any()))
                .thenReturn(List.of(product(1L, "商品1", "描述1"), product(3L, "商品3", "描述3")));

        // 传入 3 个候选，其中 2 已下架（查询未命中）
        Set<Long> displayable = enricher.findDisplayableIds(List.of(1L, 2L, 3L));

        assertEquals(Set.of(1L, 3L), displayable,
                "只返回当前上架且未逻辑删除的 ID —— 查不到的即已下架/已删除，由调用方取补集剔除");
    }

    @Test
    @DisplayName("可展示查询：全部查不到时返回空集合（= 候选全部不可展示）")
    void findDisplayableIdsReturnsEmptyWhenNoneOnShelf() {
        when(productMapper.selectList(any())).thenReturn(List.of());

        assertTrue(enricher.findDisplayableIds(List.of(1L, 2L)).isEmpty(),
                "空集合在正集语义下等于「全部不可展示」，这是与「查询失败」必须区分的根因");
    }

    @Test
    @DisplayName("可展示查询：空入参不触发查询")
    void findDisplayableIdsSkipsQueryOnEmptyInput() {
        assertTrue(enricher.findDisplayableIds(List.of()).isEmpty());
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("可展示查询：异常必须向上抛出，不得吞掉")
    void findDisplayableIdsPropagatesException() {
        when(productMapper.selectList(any())).thenThrow(new RuntimeException("DB 不可用"));

        // 本方法刻意不 catch（与 loadProducts 的内部消化风格相反）：
        // 若吞掉异常并返回空集合，调用方会把「查询失败」误读为「全部不可展示」→ 清空候选池
        assertThrows(RuntimeException.class, () -> enricher.findDisplayableIds(List.of(1L, 2L)));
    }
}
