package com.shop.rag.search;

import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 关键词检索服务
 * <p>
 * 基于 MySQL FULLTEXT INDEX（ngram 分词器，n=2）进行中文关键词搜索，
 * 与 Qdrant 向量搜索构成双路检索（Hybrid Search），结果经 RRF 合并。
 * <p>
 * 分类 / 价格过滤语义与向量路 {@code RagSearchService#passMetadataFilter} 严格对齐：
 * 分类比对 firstCategoryId，价格比对商品 SKU 价格区间，**字段缺失时一律放行**。
 * <p>
 * 过滤不放在 SQL 层：FULLTEXT 的相关性排序必须在 SQL 内完成，若在 SQL 内再叠加价格子查询，
 * 既拖慢检索、又要同步维护 shop-ai Python 侧的「逐字对齐 SQL」。改为「多取一倍 + 内存过滤」，
 * 见 {@link #OVER_FETCH_FACTOR}；这是有意的近似，不追求与向量路逐条等价。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    private final ProductMapper productMapper;
    private final SkuPriceResolver skuPriceResolver;

    /** 过滤前的取数放大倍数：SQL 排序先于过滤发生，多取一倍以抵消内存过滤造成的候选不足 */
    private static final int OVER_FETCH_FACTOR = 2;

    /**
     * 关键词检索商品（含分类 / 价格过滤）
     *
     * @param query      查询关键词（同义词扩展后的串）
     * @param limit      返回条数上限
     * @param categoryId 一级分类 ID 过滤（可选）
     * @param minPrice   最低价过滤（可选，语义：商品最高价需 >= minPrice）
     * @param maxPrice   最高价过滤（可选，语义：商品最低价需 <= maxPrice）
     * @return 检索结果列表（按 MySQL MATCH AGAINST 相关性分数降序，过滤后截断至 limit）
     */
    public List<RagHitVO> searchByKeyword(String query, int limit,
                                          Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        try {
            List<ProductDO> products = productMapper.searchByKeyword(query, limit * OVER_FETCH_FACTOR);
            if (products == null || products.isEmpty()) {
                return List.of();
            }

            Map<Long, BigDecimal[]> priceMap = resolvePrices(products);

            List<RagHitVO> hits = new ArrayList<>();
            for (ProductDO p : products) {
                if (hits.size() >= limit) {
                    break;
                }
                if (!passFilter(p, priceMap, categoryId, minPrice, maxPrice)) {
                    continue;
                }
                // 基于排名位置分配模拟分数（实际排序由 MySQL MATCH AGAINST 保证）；
                // 位次取过滤后的序号，避免被剔除的候选让分数出现空洞
                double positionScore = Math.max(0.1, 1.0 - hits.size() * 0.05);
                BigDecimal[] prices = priceMap.get(p.getId());
                hits.add(RagHitVO.builder()
                        .score((double) Math.round(positionScore * 100) / 100)
                        .productId(p.getId())
                        .name(p.getName())
                        .excerpt(p.getDescription() != null ? p.getDescription() : "")
                        .image(p.getMainImage())
                        .minPrice(prices != null ? prices[0] : null)
                        .maxPrice(prices != null ? prices[1] : null)
                        .build());
            }
            return hits;

        } catch (Exception e) {
            log.warn("MySQL FULLTEXT 检索异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索商品（无过滤条件的兼容入口）
     *
     * @param query 查询关键词
     * @param limit 返回条数上限
     * @return 检索结果列表
     */
    public List<RagHitVO> searchByKeyword(String query, int limit) {
        return searchByKeyword(query, limit, null, null, null);
    }

    /**
     * 批量解析命中商品的价格区间
     * <p>
     * 价格既参与过滤也用于填充 VO，故无论是否传入价格条件都查一次（单次批量查询，成本可控）。
     * 查询异常时返回空 Map —— 过滤环节按「字段缺失即放行」处理，避免价格查询抖动导致整体丢结果。
     *
     * @param products 命中的商品列表
     * @return productId → [最低价, 最高价]
     */
    private Map<Long, BigDecimal[]> resolvePrices(List<ProductDO> products) {
        try {
            List<Long> productIds = products.stream()
                    .map(ProductDO::getId)
                    .filter(Objects::nonNull)
                    .toList();
            return skuPriceResolver.resolve(productIds);
        } catch (Exception e) {
            log.warn("SKU 价格查询异常，本次不做价格过滤: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 分类 + 价格区间过滤（与向量路 passMetadataFilter 同语义：字段缺失一律放行）
     *
     * @param product    商品
     * @param priceMap   商品价格区间
     * @param categoryId 分类过滤条件
     * @param minPrice   最低价过滤条件
     * @param maxPrice   最高价过滤条件
     * @return true 表示通过过滤
     */
    private boolean passFilter(ProductDO product, Map<Long, BigDecimal[]> priceMap,
                               Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (categoryId != null) {
            Long firstCategoryId = product.getFirstCategoryId();
            if (firstCategoryId != null && !firstCategoryId.equals(categoryId)) {
                return false;
            }
        }

        BigDecimal[] prices = priceMap.get(product.getId());
        BigDecimal productMin = prices != null ? prices[0] : null;
        BigDecimal productMax = prices != null ? prices[1] : null;

        // 最低价过滤（商品的最高价必须 >= 查询的 minPrice）
        if (minPrice != null && productMax != null && productMax.compareTo(minPrice) < 0) {
            return false;
        }

        // 最高价过滤（商品的最低价必须 <= 查询的 maxPrice）
        if (maxPrice != null && productMin != null && productMin.compareTo(maxPrice) > 0) {
            return false;
        }

        return true;
    }
}
