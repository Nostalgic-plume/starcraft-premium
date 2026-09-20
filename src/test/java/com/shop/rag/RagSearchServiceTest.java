package com.shop.rag;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private KeywordSearchService keywordSearchService;
    @Mock
    private SynonymExpander synonymExpander;

    private RagSearchService ragSearchService;

    @BeforeEach
    void setUp() {
        ragSearchService = new RagSearchService(embeddingModel, embeddingStore,
                keywordSearchService, synonymExpander, new RrfProperties());
        when(synonymExpander.expand(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Embedding queryVector() {
        float[] vector = new float[1536];
        Arrays.fill(vector, 0.1f);
        return Embedding.from(vector);
    }

    /** 捕获向量路实际请求，返回其 maxResults（候选池大小） */
    private int capturedVectorLimit(String query, int topK) {
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(queryVector())));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<TextSegment>(List.of()));
        when(keywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
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
        verify(keywordSearchService).searchByKeyword(eq("手机"), eq(50), any(), any(), any());
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
        when(keywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(RagHitVO.builder().productId(1L).name("商品1").build()));

        List<RagHitVO> hits = ragSearchService.search("手机", -5, null, null, null);

        assertEquals(1, hits.size(), "负数 topK 应被收敛为 1 并正常返回，而不是抛 Stream.limit 异常");
        assertEquals(1L, hits.get(0).getProductId());
    }
}
