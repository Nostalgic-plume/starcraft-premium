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
 * <p>
 * 本类只产出 {@link RagCandidate}（商品 ID + 位次分数）——名称、图片、描述等展示数据
 * 由 {@link RagHitEnricher} 在选出 topK 之后统一回查，两路由此共用同一份展示数据。
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
     * 关键词检索商品（含分类 / 价格过滤），产出候选
     *
     * @param query      查询关键词（同义词扩展后的串）
     * @param limit      返回条数上限
     * @param categoryId 一级分类 ID 过滤（可选）
     * @param minPrice   最低价过滤（可选，语义：商品最高价需 &gt;= minPrice）
     * @param maxPrice   最高价过滤（可选，语义：商品最低价需 &lt;= maxPrice）
     * @return 候选列表（按 MySQL MATCH AGAINST 相关性降序，过滤后截断至 limit）
     */
    public List<RagCandidate> searchByKeyword(String query, int limit,
                                              Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        try {
            List<ProductDO> products = productMapper.searchByKeyword(query, limit * OVER_FETCH_FACTOR);
            if (products == null || products.isEmpty()) {
                return List.of();
            }

            Map<Long, SkuSnapshot> snapshotMap = resolveSnapshots(products);

            List<RagCandidate> candidates = new ArrayList<>();
            for (ProductDO product : products) {
                if (candidates.size() >= limit) {
                    break;
                }
                if (!passFilter(product, snapshotMap, categoryId, minPrice, maxPrice)) {
                    continue;
                }
                // 基于排名位置分配模拟分数（实际排序由 MySQL MATCH AGAINST 保证）；
                // 位次取过滤后的序号，避免被剔除的候选让分数出现空洞
                double positionScore = Math.max(0.1, 1.0 - candidates.size() * 0.05);
                candidates.add(new RagCandidate(product.getId(),
                        (double) Math.round(positionScore * 100) / 100));
            }
            return candidates;

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
     * @return 候选列表
     */
    public List<RagCandidate> searchByKeyword(String query, int limit) {
        return searchByKeyword(query, limit, null, null, null);
    }

    /**
     * 批量解析命中商品的 SKU 快照（价格区间 + 总库存）
     * <p>
     * 价格既参与过滤也供后续富化使用，故无论是否传入价格条件都查一次（单次批量查询，成本可控）。
     * {@code resolveSnapshot} 内部已消化查询异常并返回空 Map，此处无需再包 try/catch ——
     * 过滤环节按「字段缺失即放行」处理，避免价格查询抖动导致整体丢结果。
     *
     * @param products 命中的商品列表
     * @return productId → 快照
     */
    private Map<Long, SkuSnapshot> resolveSnapshots(List<ProductDO> products) {
        List<Long> productIds = products.stream()
                .map(ProductDO::getId)
                .filter(Objects::nonNull)
                .toList();
        return skuPriceResolver.resolveSnapshot(productIds);
    }

    /**
     * 分类 + 价格区间过滤（与向量路 passMetadataFilter 同语义：字段缺失一律放行）
     *
     * @param product     商品（FULLTEXT 查询只回填 id 与 firstCategoryId）
     * @param snapshotMap 商品 SKU 快照
     * @param categoryId  分类过滤条件
     * @param minPrice    最低价过滤条件
     * @param maxPrice    最高价过滤条件
     * @return true 表示通过过滤
     */
    private boolean passFilter(ProductDO product, Map<Long, SkuSnapshot> snapshotMap,
                               Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (categoryId != null) {
            Long firstCategoryId = product.getFirstCategoryId();
            if (firstCategoryId != null && !firstCategoryId.equals(categoryId)) {
                return false;
            }
        }

        SkuSnapshot snapshot = snapshotMap.get(product.getId());
        BigDecimal productMin = snapshot != null ? snapshot.minPrice() : null;
        BigDecimal productMax = snapshot != null ? snapshot.maxPrice() : null;

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
