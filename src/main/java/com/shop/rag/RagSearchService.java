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
 * 双路并行检索 + 加权 RRF 合并：
 * ┌─ 向量化（本地 TEI / BGE-M3）→ 官方 QdrantEmbeddingStore.search() ─┐
 * │                                                            │→ 加权 RRF 合并 → Top-K
 * └─ MySQL FULLTEXT 关键词检索（同义词扩展 + 分类/价格过滤）───┘
 * <p>
 * 两路候选池对齐：均取 {@code queryLimit = clamp(topK × 5, 50, 200)}，
 * 融合后再截断 topK。此前向量路按 topK 截断、关键词路取 queryLimit，候选池不对称，
 * topK 越小（如客服链路 topK=3）向量路越被结构性压制。
 * <p>
 * 权重与 RRF 常数由 {@link RrfProperties} 外置（默认等权 1.0/1.0、k=60，与改造前行为一致）。
 * <p>
 * 2026-08 升级 LangChain4j 1.19.0（qdrant-client 1.17.0）后，gRPC 兼容坑已修复
 * （裁决测试 QdrantGrpcCompatibilityTest 通过），向量路改走官方
 * {@link QdrantEmbeddingStore#search}，原手写 Qdrant REST 绕道（searchViaQdrantRest）已删除。
 * <p>
 * 降级策略（两级，任一环节故障都不返回空结果，仅当两路皆空时才为空）：
 * <ol>
 *   <li>嵌入模型不可用（网络异常/鉴权失败/空响应/维度不符）→ 跳过向量路，退化为纯 MySQL FULLTEXT 关键词检索；</li>
 *   <li>Qdrant 不可用 → {@link #searchViaQdrant} 返回空列表，同样退化为纯关键词检索。</li>
 * </ol>
 * 两条路径均借助 RRF 合并天然退化实现（{@link RrfMerger#merge} 对单路为空有专门分支），
 * 并输出 WARN 日志标记降级态与降级原因，便于线上排查定位。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;
    private final KeywordSearchService keywordSearchService;
    private final SynonymExpander synonymExpander;
    private final RrfProperties rrfProperties;
    private final RagProperties ragProperties;

    private static final double MIN_RELEVANCE_SCORE = 0.3;

    /** 候选池 = topK × 该倍数（再由 MIN_QUERY_LIMIT 兜底下限） */
    private static final int CANDIDATE_MULTIPLIER = 5;
    /** 候选池下限：topK 很小时也要保证向量路有足够的融合候选 */
    private static final int MIN_QUERY_LIMIT = 50;
    /** 候选池上限：防止超大 topK 放大 MySQL LIMIT 与 Qdrant maxResults */
    private static final int MAX_QUERY_LIMIT = 200;
    /** topK 上限：公开接口未校验参数，超大值会放大下游查询成本 */
    private static final int MAX_TOP_K = 100;

    /**
     * 混合搜索商品（向量 + 关键词双路 → 加权 RRF 合并）
     *
     * @param query      用户查询
     * @param topK       返回条数（越界自动收敛到 [1, 100]）
     * @param categoryId 分类过滤（可选，两路同语义：比对 firstCategoryId，缺失放行）
     * @param minPrice   最低价（可选，两路同语义：商品最高价需 >= minPrice）
     * @param maxPrice   最高价（可选，两路同语义：商品最低价需 <= maxPrice）
     * @return 混合匹配结果列表（按加权 RRF 分数降序）
     */
    public List<RagHitVO> search(String query, int topK,
                                  Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        long startNanos = System.nanoTime();  // T4 可观测性埋点

        // 0. 入参兜底：topK 为负数时 Stream.limit 会抛异常（接口 500），超大值会放大下游 LIMIT
        int safeTopK = clampTopK(topK);

        // 1. 生成查询向量：任何失败都不中断主流程，退化为纯关键词路（见类注释「降级策略」）
        float[] queryVector = embedQueryOrNull(query);

        // 2. 双路检索，候选池对齐：两路各取 queryLimit 条，融合后再截断 topK
        //    （向量路已降级时为空列表，RRF 合并天然退化为纯关键词）
        int queryLimit = clampQueryLimit(safeTopK * CANDIDATE_MULTIPLIER);

        List<RagHitVO> vectorHits = queryVector == null
                ? Collections.emptyList()
                : searchViaQdrant(queryVector, queryLimit, MIN_RELEVANCE_SCORE,
                        categoryId, minPrice, maxPrice);

        // 关键词路使用同义词扩展后的 query（向量路保持原 query，避免扩展词稀释语义重心）
        String keywordQuery = synonymExpander.expand(query);
        if (!keywordQuery.equals(query)) {
            log.debug("关键词路同义词扩展: '{}' → '{}'", query, keywordQuery);
        }
        // 关键词路的分类 / 价格过滤与向量路对齐（此前不过滤，属契约缺口，见 docs/rag-hybrid-tuning-design.md）
        List<RagHitVO> keywordHits = keywordSearchService.searchByKeyword(
                keywordQuery, queryLimit, categoryId, minPrice, maxPrice);

        log.debug("混合检索: 向量命中 {}, 关键词命中 {}, 候选池 {}, 合并取 Top-{}",
                vectorHits.size(), keywordHits.size(), queryLimit, safeTopK);

        // 4. 加权 RRF 合并（默认等权，与改造前等价）
        List<RagHitVO> merged = RrfMerger.merge(vectorHits, keywordHits, safeTopK,
                rrfProperties.getK(), rrfProperties.getWeightVector(), rrfProperties.getWeightKeyword());

        // T4 可观测性埋点：query/耗时/命中数/权重/向量路状态（轻量，不记录对话内容）
        log.info("[RAG指标] query={} topK={} 耗时{}ms 向量命中{} 关键词命中{} 合并{}条 RRF(k={},wv={},wk={}) 向量路={}",
                query, safeTopK, (System.nanoTime() - startNanos) / 1_000_000,
                vectorHits.size(), keywordHits.size(), merged.size(),
                rrfProperties.getK(), rrfProperties.getWeightVector(), rrfProperties.getWeightKeyword(),
                queryVector == null ? "已降级(纯关键词)" : "正常");
        return merged;
    }

    /**
     * topK 入参兜底：收敛到 [1, {@link #MAX_TOP_K}]
     *
     * @param topK 原始入参
     * @return 合法 topK
     */
    private static int clampTopK(int topK) {
        return Math.min(Math.max(topK, 1), MAX_TOP_K);
    }

    /**
     * 候选池兜底：收敛到 [{@link #MIN_QUERY_LIMIT}, {@link #MAX_QUERY_LIMIT}]
     *
     * @param queryLimit 原始候选池大小
     * @return 合法候选池大小
     */
    private static int clampQueryLimit(int queryLimit) {
        return Math.min(Math.max(queryLimit, MIN_QUERY_LIMIT), MAX_QUERY_LIMIT);
    }

    /**
     * 生成 query 向量，任一环节失败一律返回 {@code null}（不抛异常），由调用方降级为纯关键词检索。
     * <p>
     * 覆盖四类失败：调用异常（网络/鉴权）、返回空列表、返回空向量、维度不符。
     * 与对话链路的降级思路一致（DeepSeek → 备用模型 → 模板兜底），
     * 保证检索侧不因单一外部依赖故障而整体不可用。
     *
     * @param query 用户查询
     * @return 与 {@code rag.qdrant.dimension} 同维的查询向量；任一环节失败返回 null
     */
    private float[] embedQueryOrNull(String query) {
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(List.of(TextSegment.from(query))).content();
            if (embeddings == null || embeddings.isEmpty()) {
                log.warn("[RAG降级] 嵌入模型返回空列表，跳过向量路，退化为纯关键词检索");
                return null;
            }
            Embedding embedding = embeddings.get(0);
            if (embedding == null || embedding.vector() == null || embedding.vector().length == 0) {
                log.warn("[RAG降级] 嵌入模型返回空向量，跳过向量路，退化为纯关键词检索");
                return null;
            }
            // 维度以 rag.qdrant.dimension 为唯一事实来源。⚠️ 此处不可退化为字面量常量：
            // 本方法对维度不符只 WARN + return null（不抛异常），常量与配置漂移会导致
            // 向量被静默丢弃、向量路整体失效，而接口全部正常返回 200，极难排查。
            int expectedDimension = ragProperties.getDimension();
            if (embedding.vector().length != expectedDimension) {
                log.warn("[RAG降级] 向量维度不匹配：期望 {}（rag.qdrant.dimension），实际 {}，跳过向量路，退化为纯关键词检索",
                        expectedDimension, embedding.vector().length);
                return null;
            }
            return embedding.vector();
        } catch (Exception e) {
            log.warn("[RAG降级] 嵌入模型调用失败，跳过向量路，退化为纯关键词检索: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 官方 QdrantEmbeddingStore.search() 向量检索（gRPC，1.19.0 已修复）
     * <p>
     * 候选池对齐后不再按 topK 截断：Qdrant 请求侧 maxResults 即候选池上限，
     * 让向量路与关键词路在 RRF 中拥有同等规模的候选集合。
     * <p>
     * 异常时自动降级：返回空列表，由 RRF 合并退化为纯关键词路，并输出降级标记日志。
     */
    private List<RagHitVO> searchViaQdrant(float[] vector, int queryLimit, double minScore,
                                            Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
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

            // 遍历结果并做元数据过滤（候选数量已由 maxResults 限定，无需再按 topK 截断）
            List<RagHitVO> hits = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
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

    /**
     * 从元数据 Map 中安全取 Long 值（兼容 Long / Number / 数字字符串，解析失败返回 null）
     *
     * @param meta 商品元数据 Map（来自 TextSegment.metadata）
     * @param key  元数据键名
     * @return 转换后的 Long；值为 null 或无法转换返回 null
     */
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

    /**
     * 从元数据 Map 中安全取 BigDecimal 值（兼容 BigDecimal / Number / 数字字符串，解析失败返回 null）
     *
     * @param meta 商品元数据 Map（来自 TextSegment.metadata）
     * @param key  元数据键名
     * @return 转换后的 BigDecimal；值为 null 或无法转换返回 null
     */
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
