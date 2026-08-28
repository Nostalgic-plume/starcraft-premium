package com.shop.rag;

import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词检索服务
 * <p>
 * 基于 MySQL FULLTEXT INDEX（ngram 分词器，n=2）进行中文关键词搜索，
 * 与 Qdrant 向量搜索构成双路检索（Hybrid Search），结果经 RRF 合并。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    private final ProductMapper productMapper;

    /**
     * 关键词检索商品
     *
     * @param query 查询关键词
     * @param limit 返回条数
     * @return 检索结果列表（按 MySQL MATCH AGAINST 相关性分数降序）
     */
    public List<RagHitVO> searchByKeyword(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            List<ProductDO> products = productMapper.searchByKeyword(query, limit);
            if (products == null || products.isEmpty()) {
                return List.of();
            }

            List<RagHitVO> hits = new ArrayList<>();
            for (int i = 0; i < products.size(); i++) {
                ProductDO p = products.get(i);
                // 基于排名位置分配模拟分数（实际排序由 MySQL MATCH AGAINST 保证）
                double positionScore = Math.max(0.1, 1.0 - i * 0.05);
                hits.add(RagHitVO.builder()
                        .score((double) Math.round(positionScore * 100) / 100)
                        .productId(p.getId())
                        .name(p.getName())
                        .excerpt(p.getDescription() != null ? p.getDescription() : "")
                        .image(p.getMainImage())
                        .build());
            }
            return hits;

        } catch (Exception e) {
            log.warn("MySQL FULLTEXT 检索异常: {}", e.getMessage());
            return List.of();
        }
    }
}
