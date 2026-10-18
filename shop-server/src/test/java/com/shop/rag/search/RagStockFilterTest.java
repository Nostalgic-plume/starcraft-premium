package com.shop.rag.search;

import com.shop.rag.config.RagProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagSearchService 库存过滤测试（纯 Mockito，不依赖 MySQL / Qdrant / 嵌入服务，CI 可跑）
 * <p>
 * 锁定三条契约（见 {@code docs/rag-stock-filter-design.md}）：
 * <ol>
 *   <li>判定语义：总库存 {@code <= 0} 剔除；<b>不在 Map 中（无 SKU）放行</b>，不得用 getOrDefault 一刀切；</li>
 *   <li>过滤时机：在 RRF 合并<b>前</b>，否则合并取 topK 后再剔除会导致返回条数不足；</li>
 *   <li>缺货标记：仅在「过滤前有候选 且 过滤后为空」时为 true，候选本身为空不得误报。</li>
 * </ol>
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagSearchService - 库存过滤（售罄剔除与缺货标记）")
class RagStockFilterTest {

    @Mock
    private OpenAiEmbeddingModel embeddingModel;
    @Mock
    private QdrantEmbeddingStore embeddingStore;
    @Mock
    private KeywordSearchService keywordSearchService;
    @Mock
    private SynonymExpander synonymExpander;
    @Mock
    private SkuPriceResolver skuPriceResolver;
    @Mock
    private RagHitEnricher ragHitEnricher;

    private RagSearchService ragSearchService;

    @BeforeEach
    void setUp() {
        ragSearchService = new RagSearchService(embeddingModel, embeddingStore, keywordSearchService,
                synonymExpander, new RrfProperties(), new RagProperties(), skuPriceResolver, ragHitEnricher);
        when(synonymExpander.expand(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        // 富化做恒等映射：本测试关注库存过滤，展示数据回查由 RagHitEnricherTest 覆盖；
        // 恒等映射保证 result.hits() 忠实反映过滤后的候选（而非被 mock 成空）
        lenient().when(ragHitEnricher.enrich(anyList())).thenAnswer(invocation -> {
            List<RagCandidate> candidates = invocation.getArgument(0);
            return candidates.stream()
                    .map(candidate -> RagHitVO.builder()
                            .productId(candidate.productId())
                            .score(candidate.score())
                            .build())
                    .toList();
        });
        // 可展示性过滤默认放行（原样返回入参）：本测试聚焦库存过滤，下架过滤由 RagDisplayableFilterTest 覆盖。
        // ⚠️ 必须显式 stub —— Mockito 对 Set 返回类型默认给空集合，而「正集」语义下空集合 =
        //    候选全部不可展示 → 候选池被整体清空，本测试全部用例会失效。
        lenient().when(ragHitEnricher.findDisplayableIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return new HashSet<>(ids);
        });
    }

    /** 与 RagProperties.dimension 默认值（1024，BGE-M3）一致，否则会被维度自检丢弃 */
    private static Embedding queryVector() {
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.1f);
        return Embedding.from(vector);
    }

    /** 构造向量路命中（带 metadata，否则会被 passMetadataFilter 前的空 metadata 检查跳过） */
    private static EmbeddingMatch<TextSegment> vectorMatch(long productId, double score) {
        Metadata metadata = Metadata.from(Map.<String, Object>of(
                "productId", productId,
                "name", "商品" + productId,
                "mainImage", "img" + productId + ".jpg"));
        return new EmbeddingMatch<>(score, String.valueOf(productId), queryVector(),
                TextSegment.from("名称：商品" + productId, metadata));
    }

    private static RagCandidate keywordHit(long productId) {
        return new RagCandidate(productId, 0.5);
    }

    /** 设定两路候选：向量路按给定分数顺序，关键词路按给定 ID 顺序 */
    private void stubCandidates(List<EmbeddingMatch<TextSegment>> vectorMatches, List<Long> keywordIds) {
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(queryVector())));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(vectorMatches));
        when(keywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(keywordIds.stream().map(RagStockFilterTest::keywordHit).toList());
    }

    @Test
    @DisplayName("售罄剔除：库存为 0 的商品从结果中消失，有货商品保留")
    void outOfStockProductShouldBeRemoved() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of());
        when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of(1L, 50, 2L, 0));

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertEquals(1, result.hits().size(), "库存为 0 的商品应被剔除");
        assertEquals(1L, result.hits().get(0).getProductId());
        assertFalse(result.allOutOfStock(), "仍有有货商品时不标记为全部缺货");
    }

    @Test
    @DisplayName("部分售罄：只返回有货项，关键词路同样受过滤")
    void partialOutOfStockAcrossBothPaths() {
        stubCandidates(List.of(vectorMatch(1L, 0.9)), List.of(2L, 3L));
        // 关键词路命中 2、3；其中 3 售罄
        when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of(1L, 10, 2L, 5, 3L, 0));

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        Set<Long> ids = new HashSet<>(result.hits().stream().map(RagHitVO::getProductId).toList());
        assertEquals(Set.of(1L, 2L), ids, "两路中售罄的商品都应被剔除");
        assertFalse(result.allOutOfStock());
    }

    @Test
    @DisplayName("全部售罄：结果为空且标记 allOutOfStock，供对话链路走「缺货」话术")
    void allOutOfStockShouldBeFlagged() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of());
        when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of(1L, 0, 2L, 0));

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertTrue(result.hits().isEmpty());
        assertTrue(result.allOutOfStock(), "候选非空但全部售罄 → 必须标记，不能与「没搜到」混淆");
    }

    @Test
    @DisplayName("候选本身为空：不得误报为缺货（真实场景是没搜到）")
    void emptyCandidatesShouldNotBeFlaggedAsOutOfStock() {
        stubCandidates(List.of(), List.of());

        RagSearchResult result = ragSearchService.searchWithAvailability("不存在的商品", 5, null, null, null);

        assertTrue(result.hits().isEmpty());
        assertFalse(result.allOutOfStock(),
                "候选为空说明没搜到，标记为缺货会让模型答出「暂时缺货」的错误结论");
    }

    @Test
    @DisplayName("无 SKU 的商品放行：不因查不到库存而被误判为售罄")
    void productWithoutSkuShouldPass() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of());
        // 1 无 SKU（不在 Map 中）；2 有 SKU 且售罄
        when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of(2L, 0));

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertEquals(1, result.hits().size());
        assertEquals(1L, result.hits().get(0).getProductId(),
                "无 SKU 属数据缺失，按「缺失即放行」保留；用 getOrDefault(id, 0) 会把它误剔除");
    }

    @Test
    @DisplayName("库存查询不可用（空 Map）：全部放行，不因查询抖动丢结果")
    void stockQueryFailureShouldPassAll() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of(3L));
        when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of());

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertEquals(3, result.hits().size(), "库存未知时应放行全部候选，而不是判为售罄");
        assertFalse(result.allOutOfStock());
    }

    @Test
    @DisplayName("两路候选取并集，只发起一次库存查询")
    void stockShouldBeQueriedOnceForUnion() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of(2L, 3L));
        when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of());

        ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        // 向量路 {1,2} + 关键词路 {2,3} → 并集 {1,2,3}，去重后一次查询
        verify(skuPriceResolver, times(1)).resolveStock(
                argThat(ids -> new HashSet<>(ids).equals(Set.of(1L, 2L, 3L))));
    }

    @Test
    @DisplayName("过滤发生在 RRF 合并前：售罄项不占用 topK 名额")
    void filterShouldHappenBeforeRrfMerge() {
        // 向量路 3 条候选，其中第 2 名售罄；topK=2
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8), vectorMatch(3L, 0.7)), List.of());
        when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of(1L, 10, 2L, 0, 3L, 10));

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 2, null, null, null);

        // 合并前过滤 → 候选 {1,3} → 取 topK=2 → 仍是 2 条；
        // 若改成合并后过滤，则先取 {1,2} 再剔除 2，只剩 1 条
        assertEquals(2, result.hits().size(),
                "过滤必须在合并前，否则售罄项会占用 topK 名额导致返回条数不足");
        Set<Long> ids = new HashSet<>(result.hits().stream().map(RagHitVO::getProductId).toList());
        assertEquals(Set.of(1L, 3L), ids);
    }
}
