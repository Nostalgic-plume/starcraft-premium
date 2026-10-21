package com.shop.rag.search;

import com.shop.rag.config.RagProperties;
import com.shop.rag.config.RagSearchProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagSearchService 候选池对齐与入参兜底测试（纯 Mockito，不依赖外部服务）
 * <p>
 * 回归缺陷：向量路结果曾被 topK 截断，而关键词路取 max(topK*5, 50) 条 —— 两路候选池不对称，
 * topK 越小（客服链路 topK=3）向量路越被结构性压制。本测试锁定「两路使用同一候选池」这一契约。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagSearchService - 候选池对齐与 topK 兜底")
class RagSearchServiceTest {

    @Mock
    private OpenAiEmbeddingModel embeddingModel;
    @Mock
    private QdrantEmbeddingStore embeddingStore;
    @Mock
    private KeywordSearchRouter keywordSearchRouter;
    @Mock
    private SynonymExpander synonymExpander;
    @Mock
    private SkuPriceResolver skuPriceResolver;
    @Mock
    private RagHitEnricher ragHitEnricher;

    private RagSearchService ragSearchService;

    @BeforeEach
    void setUp() {
        ragSearchService = new RagSearchService(embeddingModel, embeddingStore, keywordSearchRouter,
                synonymExpander, new RrfProperties(), new RagProperties(), new RagSearchProperties(),
                skuPriceResolver, ragHitEnricher);
        when(synonymExpander.expand(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        // 库存查询默认不可用（空 Map → 不做库存过滤），让既有用例聚焦候选池/入参兜底本身；
        // 验证库存过滤的用例再单独覆盖该 stub
        lenient().when(skuPriceResolver.resolveStock(anyList())).thenReturn(Map.of());
        // 可展示性过滤默认放行（原样返回入参）：本测试聚焦候选池对齐，下架过滤由 RagDisplayableFilterTest 覆盖。
        // ⚠️ 必须显式 stub —— Mockito 对 Set 返回类型默认给空集合，而「正集」语义下空集合 =
        //    候选全部不可展示 → 候选池被整体清空，既有断言会全部失效。
        lenient().when(ragHitEnricher.findDisplayableIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return new HashSet<>(ids);
        });
        // 富化做恒等映射：本测试关注检索编排，展示数据回查的正确性由 RagHitEnricherTest 覆盖
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

    private static Embedding queryVector() {
        // 与 RagProperties.dimension 默认值（1024，BGE-M3）一致，否则会被维度自检丢弃
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.1f);
        return Embedding.from(vector);
    }

    /** 捕获向量路实际请求，返回其 maxResults（候选池大小） */
    private int capturedVectorLimit(String query, int topK) {
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(queryVector())));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<TextSegment>(List.of()));
        when(keywordSearchRouter.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(List.of());

        ragSearchService.search(query, topK, null, null, null);

        ArgumentCaptor<EmbeddingSearchRequest> captor =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(captor.capture());
        return captor.getValue().maxResults();
    }

    @Test
    @DisplayName("候选池下限：topK=5 时向量路收满 50 条，而非被截断到 5 条")
    void vectorPathShouldUseQueryLimitInsteadOfTopK() {
        assertEquals(50, capturedVectorLimit("手机", 5),
                "topK=5 → 候选池应为下限 50（修复前这里是 5）");

        // 关键词路必须拿到同一个候选池，否则两路在 RRF 中不对等
        verify(keywordSearchRouter).searchByKeyword(eq("手机"), eq(50), any(), any(), any());
    }

    @Test
    @DisplayName("候选池上限：topK=100 时收敛到 200，避免放大下游查询成本")
    void queryLimitShouldBeCapped() {
        assertEquals(200, capturedVectorLimit("手机", 100), "topK*5=500 应被收敛到上限 200");
    }

    @Test
    @DisplayName("topK 非法（负数）：收敛到 1，不抛异常")
    void illegalTopKShouldNotThrow() {
        // 向量路降级，聚焦入参兜底本身
        when(embeddingModel.embedAll(anyList())).thenThrow(new RuntimeException("跳过向量路"));
        when(keywordSearchRouter.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(new RagCandidate(1L, 0.9)));

        List<RagHitVO> hits = ragSearchService.search("手机", -5, null, null, null);

        assertEquals(1, hits.size(), "负数 topK 应被收敛为 1 并正常返回，而不是抛 Stream.limit 异常");
        assertEquals(1L, hits.get(0).getProductId());
    }
}
