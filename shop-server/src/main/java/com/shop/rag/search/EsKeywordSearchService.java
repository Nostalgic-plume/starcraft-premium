package com.shop.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.json.JsonData;
import com.shop.constant.ProductConstants;
import com.shop.rag.config.RagElasticsearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Elasticsearch 关键词检索服务（功能编号 001）
 * <p>
 * 与 {@link KeywordSearchService}（MySQL FULLTEXT）**同签名、同语义**，由
 * {@link KeywordSearchRouter} 按开关与健康状态二选一。
 * <p>
 * 相比 MySQL FULLTEXT 的三点差异：
 * <ol>
 *   <li>真 BM25 打分（可调 k1/b），排序质量与可解释性更好；</li>
 *   <li>IK 中文分词（索引期 ik_max_word、查询期 ik_smart），不再是 ngram 粗切；</li>
 *   <li>分类/价格过滤**下推到 ES 的 filter context**，不再需要「多取一倍 + 内存过滤」的近似。</li>
 * </ol>
 * <p>
 * 失败语义（硬约束 H6）：ES 异常一律抛 {@link KeywordSearchUnavailableException}，
 * 绝不返回空列表 —— 空列表表示「确实没搜到」，与「引擎不可用」必须区分。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsKeywordSearchService {

    /** 检索字段及权重：名称 > 品牌 > 分类 > 描述 */
    private static final String[] SEARCH_FIELDS = {
            "name^3", "brandName^2", "categoryName^1.5", "description"
    };

    /**
     * 最长匹配词的加权倍数
     *
     * <p><b>解决什么问题</b>：纯 OR 下「办公笔记本电脑」会被 IK 查询期切成
     * 「办公 / 笔记本电脑」两个词。办公椅、办公桌只命中低频高 IDF 的「办公」，
     * 得分反而压过命中「笔记本电脑」的笔记本商品（实测 top3 全是办公椅/鼠标）。
     *
     * <p><b>为什么不用 minimum_should_match</b>：查询期用 ik_smart 粗分词，长句只剩
     * 2 个词，任何 ≥50% 的门槛都等价于「两个词全命中」，笔记本商品不含「办公」会被
     * 直接过滤成空结果（实测「办公笔记本电脑」返回 0 条）。
     *
     * <p><b>本方案</b>：保留原查询的 OR 召回，额外用「查询中最长的词」对 name 字段加一个
     * should 子句加权。最长词通常是真正的品类词（笔记本电脑），泛化修饰词（办公）较短，
     * 不会获得额外加分。实测 top3 从「办公椅/办公鼠标」变为全部笔记本。
     */
    private static final float LONGEST_TERM_BOOST = 8.0f;

    /** 参与加权的词长度下限：单字太泛（「本」「机」），加权会放大噪声 */
    private static final int LONGEST_TERM_MIN_CHARS = 2;

    private final ElasticsearchClient esClient;
    private final RagElasticsearchProperties properties;

    /**
     * ES 关键词检索（含分类 / 价格过滤）
     *
     * @param query      查询关键词（同义词扩展后的串）
     * @param limit      返回条数上限
     * @param categoryId 一级分类 ID 过滤（可选）
     * @param minPrice   最低价过滤（可选，语义：商品最高价需 &gt;= minPrice）
     * @param maxPrice   最高价过滤（可选，语义：商品最低价需 &lt;= maxPrice）
     * @return 候选列表（按 BM25 分数降序）
     * @throws KeywordSearchUnavailableException ES 不可用
     */
    public List<RagCandidate> searchByKeyword(String query, int limit,
                                              Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        try {
            // 打分辅助项在进入 lambda 前算好，保证 effectively final
            String minimumShouldMatch = properties.getMinimumShouldMatch();
            boolean useMinShouldMatch = minimumShouldMatch != null && !minimumShouldMatch.isBlank();
            String longestTerm = resolveLongestTerm(query);
            SearchResponse<EsProductHit> response = esClient.search(s -> {
                s.index(properties.getIndexName())
                        .size(limit)
                        // 只回传 ID：展示数据由 RagHitEnricher 统一回查 MySQL
                        .source(src -> src.filter(f -> f.includes("productId")))
                        .query(q -> q.bool(b -> {
                            // 打分部分：多字段匹配，best_fields 取单字段最高分。
                            // minimum_should_match 默认留空（纯 OR）：查询期 ik_smart 粗分词下，
                            // 任何百分比门槛都会把长句压缩成「全命中」，反而搜不到。
                            b.must(m -> m.multiMatch(mm -> {
                                mm.query(query)
                                        .fields(List.of(SEARCH_FIELDS))
                                        .type(TextQueryType.BestFields)
                                        .operator(Operator.Or);
                                if (useMinShouldMatch) {
                                    mm.minimumShouldMatch(minimumShouldMatch);
                                }
                                return mm;
                            }));

                            // 最长词加权：品类词（笔记本电脑）压过泛化修饰词（办公），
                            // 见 LONGEST_TERM_BOOST 注释。must 存在时 should 只加分不强制。
                            if (longestTerm != null) {
                                b.should(sd -> sd.match(m -> m
                                        .field("name")
                                        .query(longestTerm)
                                        .boost(LONGEST_TERM_BOOST)));
                            }

                            // 过滤部分：filter context 不参与打分，且有查询缓存
                            b.filter(f -> f.term(t -> t.field("status").value(ProductConstants.STATUS_ON_SHELF)));
                            b.filter(f -> f.term(t -> t.field("deleted").value(0L)));
                            if (categoryId != null) {
                                b.filter(f -> f.term(t -> t.field("firstCategoryId").value(categoryId)));
                            }
                            // 最低价：商品的最高价须 >= 查询的最低价
                            if (minPrice != null) {
                                b.filter(f -> f.range(r -> r.field("maxPrice")
                                        .gte(JsonData.of(minPrice.doubleValue()))));
                            }
                            // 最高价：商品的最低价须 <= 查询的最高价
                            if (maxPrice != null) {
                                b.filter(f -> f.range(r -> r.field("minPrice")
                                        .lte(JsonData.of(maxPrice.doubleValue()))));
                            }
                            return b;
                        }));
                return s;
            }, EsProductHit.class);

            List<Hit<EsProductHit>> hits = response.hits() == null ? null : response.hits().hits();
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }

            List<RagCandidate> candidates = new ArrayList<>(hits.size());
            for (Hit<EsProductHit> hit : hits) {
                EsProductHit source = hit.source();
                if (source == null || source.productId() == null) {
                    // 脏文档（缺 productId）直接跳过，不让单条坏数据毁掉整次检索
                    continue;
                }
                double score = hit.score() == null ? 0.0 : hit.score();
                candidates.add(new RagCandidate(source.productId(),
                        (double) Math.round(score * 100) / 100));
            }
            return candidates;

        } catch (Exception e) {
            // 空结果已在上面正常返回，走到这里必然是引擎层故障
            throw new KeywordSearchUnavailableException("ES 关键词检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取查询经 IK 查询期分词后的最长词
     *
     * <p>用 {@code ik_smart}（与字段的 search_analyzer 一致）分析，取长度最长且不少于
     * {@link #LONGEST_TERM_MIN_CHARS} 的 token，用作 name 字段的加权词。
     *
     * <p>失败语义：分词异常只影响加权（退回纯 OR），不中断检索，故捕获后返回 null。
     *
     * @param query 原始查询串
     * @return 最长词；无可加权词时返回 null
     */
    private String resolveLongestTerm(String query) {
        try {
            AnalyzeResponse response = esClient.indices().analyze(a -> a
                    .index(properties.getIndexName())
                    .analyzer("ik_smart")
                    .text(query));
            return response.tokens().stream()
                    .map(AnalyzeToken::token)
                    .filter(token -> token != null && token.length() >= LONGEST_TERM_MIN_CHARS)
                    .max(Comparator.comparingInt(String::length))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("查询分词失败，跳过长词加权: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检索命中的最小文档（_source 只投影 productId）
     *
     * @param productId 商品 ID
     */
    private record EsProductHit(Long productId) {
    }
}
