package com.shop.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.shop.constant.ProductConstants;
import com.shop.rag.config.RagElasticsearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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
            SearchResponse<EsProductHit> response = esClient.search(s -> {
                s.index(properties.getIndexName())
                        .size(limit)
                        // 只回传 ID：展示数据由 RagHitEnricher 统一回查 MySQL
                        .source(src -> src.filter(f -> f.includes("productId")))
                        .query(q -> q.bool(b -> {
                            // 打分部分：多字段匹配，best_fields 取单字段最高分。
                            // minimum_should_match 用条件语法（默认 2<70%）：≤2 词要求全命中，
                            // 避免 IK 把未登录词切成单字后、OR 让单字命中带来的噪声；
                            // 长句只要求 70%，保留召回。置空则退回纯 OR。
                            b.must(m -> m.multiMatch(mm -> {
                                mm.query(query)
                                        .fields(List.of(SEARCH_FIELDS))
                                        .type(TextQueryType.BestFields)
                                        .operator(Operator.Or);
                                String minimumShouldMatch = properties.getMinimumShouldMatch();
                                if (minimumShouldMatch != null && !minimumShouldMatch.isBlank()) {
                                    mm.minimumShouldMatch(minimumShouldMatch);
                                }
                                return mm;
                            }));

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
     * 检索命中的最小文档（_source 只投影 productId）
     *
     * @param productId 商品 ID
     */
    private record EsProductHit(Long productId) {
    }
}
