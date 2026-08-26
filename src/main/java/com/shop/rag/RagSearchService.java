package com.shop.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RAG 混合检索服务（Hybrid Search）
 * <p>
 * 双路并行检索 + RRF 合并：
 * ┌─ DashScope embedding → 官方 QdrantEmbeddingStore.search() ─┐
 * │                                                            │→ RRF 合并 → Top-K
 * └─ MySQL FULLTEXT 关键词检索 ────────────────────────────────┘
 * <p>
 * 2026-08 升级 LangChain4j 1.19.0（qdrant-client 1.17.0）后，gRPC 兼容坑已修复
 * （裁决测试 QdrantGrpcCompatibilityTest 通过），向量路改走官方
 * {@link QdrantEmbeddingStore#search}，原手写 Qdrant REST 绕道（searchViaQdrantRest）已删除。
 * <p>
 * 降级策略：向量路异常时自动降级为纯 MySQL FULLTEXT 关键词路（RRF 合并天然退化为纯关键词），
 * 并输出可观测日志标记降级态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;
    private final KeywordSearchService keywordSearchService;

    private static final double MIN_RELEVANCE_SCORE = 0.3;
    private static final int EXPECTED_DIMENSION = 1536;

    /**
     * 混合搜索商品（向量 + 关键词双路 → RRF 合并）
     *
     * @param query      用户查询
     * @param topK       返回条数
     * @param categoryId 分类过滤（可选）
     * @param minPrice   最低价（可选）
     * @param maxPrice   最高价（可选）
     * @return 混合匹配结果列表（按 RRF 分数降序）
     */
    public List<RagHitVO> search(String query, int topK,
                                  Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 生成查询向量
        Embedding queryEmbedding;
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(List.of(TextSegment.from(query))).content();
            if (embeddings == null || embeddings.isEmpty()) {
                log.warn("嵌入模型返回空列表");
                return Collections.emptyList();
            }
            queryEmbedding = embeddings.get(0);
        } catch (Exception e) {
            log.warn("嵌入模型调用失败: {}", e.getMessage());
            return Collections.emptyList();
        }

        // 2. 校验向量
        if (queryEmbedding == null || queryEmbedding.vector() == null || queryEmbedding.vector().length == 0) {
            log.warn("嵌入模型返回空向量，跳过 RAG 检索");
            return Collections.emptyList();
        }
        if (queryEmbedding.vector().length != EXPECTED_DIMENSION) {
            log.warn("向量维度不匹配：期望 {}，实际 {}，跳过 RAG 检索",
                    EXPECTED_DIMENSION, queryEmbedding.vector().length);
            return Collections.emptyList();
        }

        // 3. 双路并行检索：向量 + 关键词
        int queryLimit = Math.max(topK * 5, 50);

        List<RagHitVO> vectorHits = searchViaQdrant(queryEmbedding.vector(), queryLimit,
                MIN_RELEVANCE_SCORE, categoryId, minPrice, maxPrice, topK);

        List<RagHitVO> keywordHits = keywordSearchService.searchByKeyword(query, queryLimit);

        log.debug("混合检索: 向量命中 {}, 关键词命中 {}, 合并取 Top-{}",
                vectorHits.size(), keywordHits.size(), topK);

        // 4. RRF 合并
        return RrfMerger.merge(vectorHits, keywordHits, topK);
    }

    /**
     * 官方 QdrantEmbeddingStore.search() 向量检索（gRPC，1.19.0 已修复）
     * <p>
     * 异常时自动降级：返回空列表，由 RRF 合并退化为纯关键词路，并输出降级标记日志。
     */
    private List<RagHitVO> searchViaQdrant(float[] vector, int queryLimit, double minScore,
                                            Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                            int topK) {
        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(vector))
                    .maxResults(queryLimit)
                    .minScore(minScore)
                    .build();
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = result.matches();
            if (matches == null || matches.isEmpty()) {
                return Collections.emptyList();
            }

            // 遍历结果，过滤元数据，按 topK 截断
            List<RagHitVO> hits = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (hits.size() >= topK) break;

                TextSegment segment = match.embedded();
                if (segment == null) continue;
                Map<String, Object> meta = segment.metadata().toMap();
                if (meta == null || meta.isEmpty()) continue;

                // 业务元数据过滤（分类/价格）
                if (!passMetadataFilter(meta, categoryId, minPrice, maxPrice)) continue;

                // 构建 VO
                hits.add(RagHitVO.builder()
                        .score((double) Math.round(match.score() * 100) / 100)
                        .excerpt(truncate(segment.text(), 120))
                        .productId(metaLong(meta, "productId"))
                        .name(metaText(meta, "name"))
                        .image(metaText(meta, "mainImage"))
                        .minPrice(metaBigDecimal(meta, "minPrice"))
                        .maxPrice(metaBigDecimal(meta, "maxPrice"))
                        .build());
            }
            return hits;

        } catch (Exception e) {
            log.warn("Qdrant 不可用，已降级为纯关键词检索: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== 元数据过滤 ==========

    /** 按分类 ID + 价格区间过滤搜索结果（元数据来自 TextSegment.metadata） */
    private boolean passMetadataFilter(Map<String, Object> meta, Long categoryId,
                                        BigDecimal minPrice, BigDecimal maxPrice) {
        // 分类过滤
        if (categoryId != null) {
            Long cat = metaLong(meta, "firstCategoryId");
            if (cat != null && !cat.equals(categoryId)) {
                return false;
            }
        }

        // 最低价过滤（商品的 maxPrice 必须 >= 查询的 minPrice）
        if (minPrice != null) {
            BigDecimal maxP = metaBigDecimal(meta, "maxPrice");
            if (maxP != null && maxP.compareTo(minPrice) < 0) {
                return false;
            }
        }

        // 最高价过滤（商品的 minPrice 必须 <= 查询的 maxPrice）
        if (maxPrice != null) {
            BigDecimal minP = metaBigDecimal(meta, "minPrice");
            if (minP != null && minP.compareTo(maxPrice) > 0) {
                return false;
            }
        }

        return true;
    }

    // ========== 元数据安全取值辅助（Map<String,Object> 版） ==========

    private static String metaText(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long metaLong(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.valueOf(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static BigDecimal metaBigDecimal(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (v instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
