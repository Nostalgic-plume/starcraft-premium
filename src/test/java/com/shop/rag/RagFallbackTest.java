package com.shop.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RagSearchService 降级链路测试（纯 Mockito，不依赖 MySQL / Qdrant / 嵌入服务，CI 可跑）
 * <p>
 * 回归缺陷：嵌入模型失败时曾直接 return emptyList()，导致 DashScope 抖动/欠费/Key 失效时
 * 整条商品搜索返回空——连纯关键词本可命中的 query 一起失效，且与类注释宣称的
 * 「向量路异常时自动降级为纯关键词」不符。
 * <p>
 * 修复后：嵌入模型任一环节失败均降级为纯关键词检索，且不再访问向量库。
 * 用 mock 抛异常而非真实无效 Key，避免测试依赖外网（OpenAI client 默认重试 + 2 分钟超时）。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagSearchService - 嵌入模型故障降级为纯关键词检索")
class RagFallbackTest {

    private static final Long PRODUCT_ID = 4L;

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
        ragSearchService = new RagSearchService(
                embeddingModel, embeddingStore, keywordSearchService, synonymExpander, new RrfProperties());
        // 同义词扩展原样返回，聚焦降级逻辑本身
        when(synonymExpander.expand(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        // 关键词路固定命中 1 条，用于断言「降级后仍有结果」
        when(keywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(RagHitVO.builder().productId(PRODUCT_ID).name("海尔 滚筒洗衣机").build()));
    }

    @Test
    @DisplayName("嵌入模型调用异常（DashScope 不可用）→ 降级纯关键词，不返回空、不查向量库")
    void degradeWhenEmbeddingThrows() {
        when(embeddingModel.embedAll(anyList()))
                .thenThrow(new RuntimeException("模拟 DashScope 不可用"));

        List<RagHitVO> hits = ragSearchService.search("洗衣机", 5, null, null, null);

        assertFalse(hits.isEmpty(), "嵌入模型异常时应降级为纯关键词检索，而非返回空结果");
        assertEquals(PRODUCT_ID, hits.get(0).getProductId(), "降级结果应来自关键词路");
        verifyNoInteractions(embeddingStore);
    }

    @Test
    @DisplayName("嵌入模型返回空列表 → 同样降级纯关键词")
    void degradeWhenEmbeddingReturnsEmptyList() {
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.<Embedding>of()));

        List<RagHitVO> hits = ragSearchService.search("洗衣机", 5, null, null, null);

        assertFalse(hits.isEmpty(), "嵌入模型返回空列表时应降级为纯关键词检索");
        verifyNoInteractions(embeddingStore);
    }

    @Test
    @DisplayName("向量维度不符（模型被换/配置错）→ 同样降级纯关键词")
    void degradeWhenDimensionMismatch() {
        Embedding wrongDimension = Embedding.from(new float[]{0.1f, 0.2f});
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(wrongDimension)));

        List<RagHitVO> hits = ragSearchService.search("洗衣机", 5, null, null, null);

        assertFalse(hits.isEmpty(), "向量维度不符时应降级为纯关键词检索");
        verifyNoInteractions(embeddingStore);
    }
}
