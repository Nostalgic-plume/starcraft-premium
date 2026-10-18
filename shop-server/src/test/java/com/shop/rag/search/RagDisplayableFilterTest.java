package com.shop.rag.search;

import com.shop.rag.config.RagProperties;
import com.shop.rag.config.RagSearchProperties;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagSearchService 可展示性过滤测试（纯 Mockito，不依赖 MySQL / Qdrant / 嵌入服务，CI 可跑）
 * <p>
 * 锁定四条契约（见 {@code docs/rag-candidate-cleanup-design.md}）：
 * <ol>
 *   <li><b>正集语义</b>：以「当前可展示 id 集合」取补集剔除，空正集 = 全部剔除，
 *       与「查询异常」（= 不过滤）语义相反，两者必须严格区分；</li>
 *   <li><b>异常即放行</b>：一次 DB 抖动不得升级为「检索全空」；</li>
 *   <li><b>过滤时机</b>：在 RRF 合并<b>前</b>，否则不可展示项会占用 topK 名额导致条数不足；</li>
 *   <li><b>不误报缺货</b>：因下架被剔除属「不可售」，不得标记 {@code allOutOfStock}
 *       （那会让客服答出「暂时缺货」，与事实不符）。</li>
 * </ol>
 * 本测试与 {@link RagStockFilterTest} 互补：那个测售罄剔除，这个测下架 / 已删除剔除。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagSearchService - 可展示性过滤（下架 / 已删除剔除）")
class RagDisplayableFilterTest {

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
                synonymExpander, new RrfProperties(), new RagProperties(), new RagSearchProperties(),
                skuPriceResolver, ragHitEnricher);
        when(synonymExpander.expand(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        // 库存查询默认不可用（空 Map → 不做库存过滤），让本测试聚焦可展示性过滤
        lenient().when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of());
        // 富化做恒等映射：本测试关注候选池清洗，展示数据回查由 RagHitEnricherTest 覆盖
        lenient().when(ragHitEnricher.enrich(anyList())).thenAnswer(invocation -> {
            List<RagCandidate> candidates = invocation.getArgument(0);
            return candidates.stream()
                    .map(candidate -> RagHitVO.builder()
                            .productId(candidate.productId())
                            .score(candidate.score())
                            .build())
                    .toList();
        });
    }

    /** 与 RagProperties.dimension 默认值（1024，BGE-M3）一致，否则会被维度自检丢弃 */
    private static Embedding queryVector() {
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.1f);
        return Embedding.from(vector);
    }

    /**
     * 构造向量路命中
     * <p>
     * metadata 只含 {@code productId} —— 与精简后的 payload 结构保持一致
     * （2026-10-16 已删除 name / mainImage 两个无消费点字段）。
     */
    private static EmbeddingMatch<TextSegment> vectorMatch(long productId, double score) {
        Metadata metadata = Metadata.from(Map.<String, Object>of("productId", productId));
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
                .thenReturn(keywordIds.stream().map(RagDisplayableFilterTest::keywordHit).toList());
    }

    /** 设定「当前可展示」正集：不在集合内的候选会被判为已下架 / 已删除而剔除 */
    private void stubDisplayableIds(Long... displayableIds) {
        lenient().when(ragHitEnricher.findDisplayableIds(anyCollection()))
                .thenReturn(new HashSet<>(Arrays.asList(displayableIds)));
    }

    @Test
    @DisplayName("下架剔除：不在可展示正集内的候选从结果中消失，其余保留")
    void notDisplayableProductShouldBeRemoved() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of());
        stubDisplayableIds(1L);

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertEquals(1, result.hits().size(), "不在可展示正集内的商品应被剔除（幽灵索引）");
        assertEquals(1L, result.hits().get(0).getProductId());
    }

    @Test
    @DisplayName("正集为空 = 全部不可展示 → 全部剔除（与「查询异常」语义相反）")
    void emptyDisplayableSetShouldRemoveAll() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of());
        stubDisplayableIds();

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertTrue(result.hits().isEmpty(),
                "正集为空意味着候选全部已下架或已逻辑删除（正集语义自动覆盖 deleted=1）→ 必须全部剔除；"
                        + "若把空集合按「不过滤」处理，这道过滤就永远不会生效");
    }

    @Test
    @DisplayName("可展示查询异常：全部放行，不因一次 DB 抖动清空候选池")
    void displayableQueryFailureShouldPassAll() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of(3L));
        when(ragHitEnricher.findDisplayableIds(anyCollection()))
                .thenThrow(new RuntimeException("DB down"));

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertEquals(3, result.hits().size(),
                "查询异常必须放行全部候选 —— 否则检索会因一次 DB 抖动整体返回空，比不过滤更糟");
    }

    @Test
    @DisplayName("候选本身为空：不发起可展示查询")
    void emptyCandidatesShouldSkipQuery() {
        stubCandidates(List.of(), List.of());

        ragSearchService.searchWithAvailability("不存在的商品", 5, null, null, null);

        verify(ragHitEnricher, never()).findDisplayableIds(anyCollection());
    }

    @Test
    @DisplayName("两路候选取并集，只发起一次可展示查询")
    void displayableShouldBeQueriedOnceForUnion() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of(2L, 3L));
        stubDisplayableIds(1L, 2L, 3L);

        ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        // 向量路 {1,2} + 关键词路 {2,3} → 并集 {1,2,3}，去重后一次查询
        verify(ragHitEnricher, times(1)).findDisplayableIds(
                argThat(ids -> new HashSet<>(ids).equals(Set.of(1L, 2L, 3L))));
    }

    @Test
    @DisplayName("过滤发生在 RRF 合并前：不可展示项不占用 topK 名额")
    void filterShouldHappenBeforeRrfMerge() {
        // 向量路 3 条候选，其中第 2 名已下架；topK=2
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8), vectorMatch(3L, 0.7)), List.of());
        stubDisplayableIds(1L, 3L);

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 2, null, null, null);

        // 合并前过滤 → 候选 {1,3} → 取 topK=2 → 仍是 2 条；
        // 若改成合并后过滤，则先取 {1,2} 再剔除 2，只剩 1 条
        assertEquals(2, result.hits().size(),
                "过滤必须在合并前，否则已下架项会占用 topK 名额导致返回条数不足");
        Set<Long> ids = new HashSet<>(result.hits().stream().map(RagHitVO::getProductId).toList());
        assertEquals(Set.of(1L, 3L), ids);
    }

    @Test
    @DisplayName("因下架被剔除不得标记为缺货（「不可售」≠「缺货」）")
    void offShelfRemovalShouldNotBeFlaggedAsOutOfStock() {
        stubCandidates(List.of(vectorMatch(1L, 0.9), vectorMatch(2L, 0.8)), List.of());
        stubDisplayableIds();

        RagSearchResult result = ragSearchService.searchWithAvailability("手机", 5, null, null, null);

        assertTrue(result.hits().isEmpty());
        assertFalse(result.allOutOfStock(),
                "下架 ≠ 缺货：误报会让客服答出「这款暂时缺货」，而真实原因是商品已下架不可售");
    }
}
