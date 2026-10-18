package com.shop.rag.search;

import com.shop.rag.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** SKU 维度聚合：库存过滤需要「商品 → 总库存」，与索引侧/关键词侧共用同一聚合来源 */
    private final SkuPriceResolver skuPriceResolver;
    /** 结果富化：融合出 topK 后回查商品展示数据（名称/主图/描述）并组装最终命中项 */
    private final RagHitEnricher ragHitEnricher;

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
     * <p>
     * 等价于 {@link #searchWithAvailability} 丢弃可售性信息的简化入口，
     * 供只关心命中列表的调用方（公开检索接口）使用；签名与行为保持不变。
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
        return searchWithAvailability(query, topK, categoryId, minPrice, maxPrice).hits();
    }

    /**
     * 混合搜索商品（带可售性信息）
     * <p>
     * 检索逻辑与 {@link #search} 完全一致，额外返回「是否因全部售罄而被过滤空」，
     * 供对话链路区分「缺货」与「没搜到」两种空结果。
     *
     * @param query      用户查询
     * @param topK       返回条数（越界自动收敛到 [1, 100]）
     * @param categoryId 分类过滤（可选，两路同语义：比对 firstCategoryId，缺失放行）
     * @param minPrice   最低价（可选，两路同语义：商品最高价需 >= minPrice）
     * @param maxPrice   最高价（可选，两路同语义：商品最低价需 <= maxPrice）
     * @return 检索结果（命中列表按加权 RRF 分数降序 + 缺货标记）
     */
    public RagSearchResult searchWithAvailability(String query, int topK,
                                  Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank()) {
            return new RagSearchResult(List.of(), false);
        }
        long startNanos = System.nanoTime();  // T4 可观测性埋点

        // 0. 入参兜底：topK 为负数时 Stream.limit 会抛异常（接口 500），超大值会放大下游 LIMIT
        int safeTopK = clampTopK(topK);

        // 1. 生成查询向量：任何失败都不中断主流程，退化为纯关键词路（见类注释「降级策略」）
        float[] queryVector = embedQueryOrNull(query);

        // 2. 双路检索，候选池对齐：两路各取 queryLimit 条，融合后再截断 topK
        //    （向量路已降级时为空列表，RRF 合并天然退化为纯关键词）
        int queryLimit = clampQueryLimit(safeTopK * CANDIDATE_MULTIPLIER);

        List<RagCandidate> vectorHits = queryVector == null
                ? Collections.emptyList()
                : searchViaQdrant(queryVector, queryLimit, MIN_RELEVANCE_SCORE,
                        categoryId, minPrice, maxPrice);

        // 关键词路使用同义词扩展后的 query（向量路保持原 query，避免扩展词稀释语义重心）
        String keywordQuery = synonymExpander.expand(query);
        if (!keywordQuery.equals(query)) {
            log.debug("关键词路同义词扩展: '{}' → '{}'", query, keywordQuery);
        }
        // 关键词路的分类 / 价格过滤与向量路对齐（此前不过滤，属契约缺口，见 docs/rag-hybrid-tuning-design.md）
        List<RagCandidate> keywordHits = keywordSearchService.searchByKeyword(
                keywordQuery, queryLimit, categoryId, minPrice, maxPrice);

        log.debug("混合检索: 向量命中 {}, 关键词命中 {}, 候选池 {}, 合并取 Top-{}",
                vectorHits.size(), keywordHits.size(), queryLimit, safeTopK);

        // 3.5 候选池清洗：在 RRF 合并【前】剔除「不可展示 / 不可售」的商品，保证候选池干净。
        //     必须前置——合并后再过滤会导致返回条数不足 topK（客服链路 topK=3，滤掉 2 条就只剩 1 条）。
        //     两类来源：① 已下架 / 已逻辑删除的幽灵索引（查 product，见 findNotDisplayableIds）
        //               ② 全部 SKU 售罄（查 sku，见 findOutOfStockIds）
        //     两路候选取并集各查一次；两类查询异常都「放行不过滤」，真实状态由 topK 之后的回查兜底。
        Set<Long> notDisplayableIds = findNotDisplayableIds(vectorHits, keywordHits);
        Set<Long> outOfStockIds = findOutOfStockIds(vectorHits, keywordHits);
        Set<Long> excludedIds = new HashSet<>(notDisplayableIds);
        excludedIds.addAll(outOfStockIds);
        List<RagCandidate> vectorCandidates = removeByIds(vectorHits, excludedIds);
        List<RagCandidate> keywordCandidates = removeByIds(keywordHits, excludedIds);

        // 4. 加权 RRF 合并（默认等权，与改造前等价）—— 融合只需 ID 与分数
        List<RagCandidate> merged = RrfMerger.merge(vectorCandidates, keywordCandidates, safeTopK,
                rrfProperties.getK(), rrfProperties.getWeightVector(), rrfProperties.getWeightKeyword());

        // 5. 富化：拿到 topK 之后才回查展示数据（名称/主图/描述），
        //    回查量从候选池（50~200 条）降到 topK（3~10 条）；两路的 excerpt 由此统一为商品描述
        List<RagHitVO> hits = ragHitEnricher.enrich(merged);

        // T4 可观测性埋点：耗时/命中数/库存剔除数/富化数/权重/向量路状态。
        // 刻意不记录 query 原文 —— 用户输入可能含 PII（如「138xxxx 用的手机」），
        // 改记其长度：既能区分「短词命中差」与「长句未命中」，又可凭耗时/命中数与前后日志关联排查
        log.info("[RAG指标] queryLen={} topK={} 耗时{}ms 向量命中{} 关键词命中{} 下架剔除{} 库存剔除{} 合并{}条 富化{}条 RRF(k={},wv={},wk={}) 向量路={}",
                query.length(), safeTopK, (System.nanoTime() - startNanos) / 1_000_000,
                vectorHits.size(), keywordHits.size(), notDisplayableIds.size(), outOfStockIds.size(),
                merged.size(), hits.size(),
                rrfProperties.getK(), rrfProperties.getWeightVector(), rrfProperties.getWeightKeyword(),
                queryVector == null ? "已降级(纯关键词)" : "正常");

        // 缺货标记：三个条件缺一不可 ——「过滤前有候选」且「过滤后为空」且「剔除原因中不含『不可展示』」。
        //   ① 候选本身为空说明真的没搜到，不可误报为缺货；
        //   ② 若空结果里掺了「已下架 / 已逻辑删除」的剔除，真实原因是商品不可售而非缺货，
        //      误报会让客服答出「这款暂时缺货」，与事实不符 —— 对话链路的两种话术据此分流。
        boolean allOutOfStock = merged.isEmpty()
                && notDisplayableIds.isEmpty()
                && (!vectorHits.isEmpty() || !keywordHits.isEmpty());
        return new RagSearchResult(hits, allOutOfStock);
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
     * 找出两路候选中「全部 SKU 售罄」的商品 ID 集合
     * <p>
     * 对两路候选的**并集**批量查询一次库存，避免两路各查一次 sku 表。
     * <p>
     * 判定规则（设计文档 §2.2）：总库存 {@code <= 0} 视为售罄。
     * ⚠️ 必须区分两种缺失：
     * 「在 Map 中 且 {@code <= 0}」= 明确售罄 → 剔除；
     * 「不在 Map 中」= 无 SKU 商品（数据缺失）→ 按「缺失即放行」不剔除。
     * 用 {@code getOrDefault(id, 0)} 会把后者误判为售罄。
     *
     * @param vectorHits  向量路候选
     * @param keywordHits 关键词路候选
     * @return 需剔除的售罄商品 ID 集合；无候选或库存查询不可用时返回空集合（不过滤）
     */
    private Set<Long> findOutOfStockIds(List<RagCandidate> vectorHits, List<RagCandidate> keywordHits) {
        Set<Long> candidateIds = new LinkedHashSet<>();
        collectProductIds(vectorHits, candidateIds);
        collectProductIds(keywordHits, candidateIds);
        if (candidateIds.isEmpty()) {
            return Set.of();
        }

        Map<Long, Integer> stockMap = skuPriceResolver.resolveStock(new ArrayList<>(candidateIds));
        if (stockMap.isEmpty()) {
            // 查询异常（resolveStock 内部已 WARN）或候选商品均无 SKU —— 两种都按不过滤处理
            return Set.of();
        }

        Set<Long> outOfStockIds = new HashSet<>();
        for (Long productId : candidateIds) {
            Integer totalStock = stockMap.get(productId);
            if (totalStock != null && totalStock <= 0) {
                outOfStockIds.add(productId);
            }
        }
        return outOfStockIds;
    }

    /**
     * 找出两路候选中「当前不可展示」的商品 ID 集合（已下架 / 已逻辑删除）
     * <p>
     * 与 {@link #findOutOfStockIds} 并列构成候选池清洗的两个来源，同样必须在 RRF 合并**前**生效
     * （理由见上：合并后过滤会导致返回条数不足 topK）。
     * <p>
     * 为什么必须有这道过滤：向量库与 MySQL 的一致性靠「索引更新尽力而为」维持
     * （索引调用一律 try-catch 只 WARN、且与 DB 写入非事务），故可能残留**已下架商品的幽灵索引**。
     * payload 里没有 status 字段，Qdrant 侧无法过滤；此前唯一防线是 topK 之后的回查剔除，
     * 那条路径解决不了「条数不足」。
     * <p>
     * 与 {@link #findOutOfStockIds} 的两点差异（易踩）：
     * <ol>
     *   <li><b>反集 vs 正集</b>：本方法按正集语义取补集（先查「可展示的」），
     *       因为 {@code deleted = 1} 被 @TableLogic 自动排除，正集能一次覆盖「已下架」与
     *       「已逻辑删除」，反集（查 status != 上架）只能查到前者；</li>
     *   <li><b>空集合 ≠ 不过滤</b>：正集为空意味着「候选全部不可展示」→ 全部剔除。
     *       故必须与「查询异常」（→ 不过滤）严格区分，实现上靠 try-catch 分离，
     *       不能像库存那样用「空 Map 表示查询失败」。</li>
     * </ol>
     * <p>
     * 查询异常一律放行（返回空集合）：不丢结果，真实状态由回查兜底 ——
     * 避免一次 DB 抖动升级为「检索全空」。
     *
     * @param vectorHits  向量路候选
     * @param keywordHits 关键词路候选
     * @return 需剔除的不可展示商品 ID 集合；无候选或查询异常时返回空集合（不过滤）
     */
    private Set<Long> findNotDisplayableIds(List<RagCandidate> vectorHits, List<RagCandidate> keywordHits) {
        Set<Long> candidateIds = new LinkedHashSet<>();
        collectProductIds(vectorHits, candidateIds);
        collectProductIds(keywordHits, candidateIds);
        if (candidateIds.isEmpty()) {
            return Set.of();
        }

        Set<Long> displayableIds;
        try {
            displayableIds = ragHitEnricher.findDisplayableIds(candidateIds);
        } catch (Exception e) {
            // 异常即放行：不因状态查询抖动丢结果（与库存过滤同一约定）
            log.warn("候选池商品状态查询异常，本次不做下架过滤: {}", e.getMessage());
            return Set.of();
        }
        if (displayableIds == null) {
            // 契约上不应发生（实现保证非 null）；防御性按「不过滤」处理，避免误清空候选池
            log.warn("可展示商品查询返回 null，本次不做下架过滤");
            return Set.of();
        }
        if (displayableIds.isEmpty()) {
            // ⚠️ 与上面的异常分支语义相反：正集为空 = 候选全部不可展示 → 全部剔除（不是「不过滤」）
            return new HashSet<>(candidateIds);
        }

        Set<Long> notDisplayableIds = new HashSet<>(candidateIds);
        notDisplayableIds.removeAll(displayableIds);
        return notDisplayableIds;
    }

    /** 收集候选中的商品 ID（跳过 null；去重由目标 Set 保证） */
    private static void collectProductIds(List<RagCandidate> candidates, Set<Long> target) {
        if (candidates == null) {
            return;
        }
        for (RagCandidate candidate : candidates) {
            if (candidate != null && candidate.productId() != null) {
                target.add(candidate.productId());
            }
        }
    }

    /**
     * 按 ID 集合剔除候选，保持原顺序（候选池清洗的两个来源共用：不可展示 ∪ 售罄）
     *
     * @param candidates  候选列表（可为 null）
     * @param excludedIds 待剔除的商品 ID 集合（空集合时原样返回，避免无谓的流式开销）
     * @return 过滤后的列表
     */
    private static List<RagCandidate> removeByIds(List<RagCandidate> candidates, Set<Long> excludedIds) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (excludedIds.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> candidate != null
                        && (candidate.productId() == null || !excludedIds.contains(candidate.productId())))
                .collect(Collectors.toList());
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
    private List<RagCandidate> searchViaQdrant(float[] vector, int queryLimit, double minScore,
                                            Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        try {
            EmbeddingSearchRequest.EmbeddingSearchRequestBuilder requestBuilder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(vector))
                    .maxResults(queryLimit)
                    .minScore(minScore);

            // 过滤下推：让 Qdrant 先按分类/价格过滤再取数，避免「取回后丢弃」导致向量路候选池缩水
            //（关键词路有 OVER_FETCH_FACTOR 缓冲，向量路此前没有 —— 窄筛选下两路规模不对等）。
            // 构造失败一律回退为不下推（等价于改造前行为），由应用层 passMetadataFilter 兜底。
            Filter filter = null;
            try {
                filter = buildMetadataFilter(categoryId, minPrice, maxPrice);
            } catch (Exception e) {
                log.warn("元数据过滤条件构造失败，本次不下推（应用层兜底）: {}", e.getMessage());
            }
            if (filter != null) {
                requestBuilder.filter(filter);
            }

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(requestBuilder.build());
            List<EmbeddingMatch<TextSegment>> matches = result.matches();
            // 下推生效证据：Qdrant 返回条数 < maxResults 说明过滤发生在服务端；
            // 恒等于 maxResults 则说明 filter 未生效（此时靠下方 passMetadataFilter 兜住正确性）。
            // ⚠️ 不能只看最终结果判断是否生效 —— 下推成功与未生效时返回的结果是一样的。
            if (filter != null) {
                log.info("[RAG向量路] 过滤下推 Qdrant 返回 {}/{} 条（小于 maxResults 表示下推生效）",
                        matches == null ? 0 : matches.size(), queryLimit);
            }
            if (matches == null || matches.isEmpty()) {
                return Collections.emptyList();
            }

            // 遍历结果并做元数据过滤（候选数量已由 maxResults 限定，无需再按 topK 截断）
            List<RagCandidate> candidates = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                TextSegment segment = match.embedded();
                if (segment == null) continue;
                Map<String, Object> meta = segment.metadata().toMap();
                if (meta == null || meta.isEmpty()) continue;

                // 业务元数据过滤（分类/价格）——仍需 payload 字段，故这些字段保留在 Qdrant
                if (!passMetadataFilter(meta, categoryId, minPrice, maxPrice)) continue;

                // 只产出 ID 与分数：名称/主图/描述等展示数据由 RagHitEnricher 在选出 topK 后回查。
                // segment.text() 与 metadata 中的展示字段不再进入检索层。
                Long productId = metaLong(meta, "productId");
                if (productId == null) continue;
                candidates.add(new RagCandidate(productId,
                        (double) Math.round(match.score() * 100) / 100));
            }
            return candidates;

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

    /**
     * 构造下推给 Qdrant 的元数据过滤条件
     * <p>
     * 语义必须与 {@link #passMetadataFilter(Map, Long, BigDecimal, BigDecimal)} **逐条一致**：
     * 分类比对 {@code firstCategoryId}；价格是**区间相交**（商品最高价 &gt;= 查询最低价，
     * 且商品最低价 &lt;= 查询最高价），不是「商品价格完全落在区间内」。
     * <p>
     * 三个条件都为空时返回 {@code null}（不下推，请求与改造前完全一致）。
     * <p>
     * ⚠️ 下推**不替代**应用层过滤：若 LangChain4j 到 Qdrant 的 filter 转换失效
     * （如字段类型与 payload 不符），应用层 {@link #passMetadataFilter} 仍是正确性的最后保障。
     *
     * @param categoryId 一级分类 ID（可空）
     * @param minPrice   最低价（可空）
     * @param maxPrice   最高价（可空）
     * @return 组合后的过滤条件；三者皆空返回 null
     */
    private static Filter buildMetadataFilter(Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        Filter filter = null;
        if (categoryId != null) {
            filter = MetadataFilterBuilder.metadataKey("firstCategoryId").isEqualTo(categoryId);
        }
        if (minPrice != null) {
            Filter condition = MetadataFilterBuilder.metadataKey("maxPrice")
                    .isGreaterThanOrEqualTo(minPrice.doubleValue());
            filter = filter == null ? condition : filter.and(condition);
        }
        if (maxPrice != null) {
            Filter condition = MetadataFilterBuilder.metadataKey("minPrice")
                    .isLessThanOrEqualTo(maxPrice.doubleValue());
            filter = filter == null ? condition : filter.and(condition);
        }
        return filter;
    }

    // ========== 元数据安全取值辅助（Map<String,Object> 版） ==========

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

}
