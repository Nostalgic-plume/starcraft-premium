package com.shop.rag;

import cn.hutool.core.collection.CollUtil;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * RAG 语义检索服务
 * 将用户查询转为向量 → Qdrant 检索 → 元数据过滤 → 返回结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchService {

    private final OllamaEmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;

    private static final double MIN_RELEVANCE_SCORE = 0.6;
    private static final int EXPECTED_DIMENSION = 1024;

    /**
     * 语义搜索商品
     *
     * @param query      用户查询
     * @param topK       返回条数
     * @param categoryId 分类过滤（可选）
     * @param minPrice   最低价（可选）
     * @param maxPrice   最高价（可选）
     * @return 语义匹配结果列表
     */
    public List<RagHitVO> search(String query, int topK,
                                  Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 生成查询向量
        Embedding queryEmbedding;
        try {
            queryEmbedding = embeddingModel.embed(query).content();
        } catch (Exception e) {
            log.warn("Ollama 嵌入模型调用失败（Ollama 未运行或模型未加载）: {}", e.getMessage());
            return Collections.emptyList();
        }

        // 2. 校验向量有效性：非空、维度匹配
        if (queryEmbedding == null || queryEmbedding.vector() == null || queryEmbedding.vector().length == 0) {
            log.warn("Ollama 返回空向量（可能模型未加载或返回异常），跳过 RAG 检索");
            return Collections.emptyList();
        }
        if (queryEmbedding.vector().length != EXPECTED_DIMENSION) {
            log.warn("向量维度不匹配：期望 {}，实际 {}，跳过 RAG 检索", EXPECTED_DIMENSION, queryEmbedding.vector().length);
            return Collections.emptyList();
        }

        // 3. Qdrant 检索
        List<EmbeddingMatch<TextSegment>> matches;
        try {
            matches = embeddingStore.findRelevant(
                    queryEmbedding, topK * 3, MIN_RELEVANCE_SCORE);
        } catch (Exception e) {
            log.warn("RAG 检索失败（向量维度不匹配或 Qdrant 未就绪）: {}", e.getMessage());
            return Collections.emptyList();
        }

        if (CollUtil.isEmpty(matches)) {
            return Collections.emptyList();
        }

        // 4. 元数据过滤 + 转 VO
        return matches.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.embedded() != null)
                .filter(m -> filterByMetadata(m.embedded(), categoryId))
                .limit(topK)
                .map(this::toHitVO)
                .collect(Collectors.toList());
    }

    /**
     * 按分类过滤
     */
    private boolean filterByMetadata(TextSegment segment, Long categoryId) {
        if (categoryId == null) {
            return true;
        }
        Object catId = segment.metadata().get("firstCategoryId");
        if (catId == null) {
            return true; // 无分类信息则通过
        }
        // firstCategoryId 是 Number（Long → Jackson 可能转 Number）
        return String.valueOf(catId).equals(String.valueOf(categoryId));
    }

    /**
     * EmbeddingMatch 转 VO
     */
    private RagHitVO toHitVO(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        var meta = segment.metadata();

        RagHitVO.RagHitVOBuilder builder = RagHitVO.builder()
                .score((double) Math.round(match.score() * 100) / 100)
                .excerpt(truncate(segment.text(), 120));

        // 从 metadata 还原字段
        Object productId = meta.get("productId");
        if (productId != null) {
            builder.productId(Long.valueOf(String.valueOf(productId)));
        }
        Object name = meta.get("name");
        if (name != null) {
            builder.name(String.valueOf(name));
        }
        Object image = meta.get("mainImage");
        if (image != null) {
            builder.image(String.valueOf(image));
        }

        return builder.build();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
