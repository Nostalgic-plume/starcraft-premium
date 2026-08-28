package com.shop.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * RAG 语义搜索结果 VO
 *
 * @author shop
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagHitVO {
    /** 商品ID */
    private Long productId;
    /** 商品名称 */
    private String name;
    /** 主图 */
    private String image;
    /** 最低价格 */
    private BigDecimal minPrice;
    /** 最高价格 */
    private BigDecimal maxPrice;
    /** 相关性分数 */
    private Double score;
    /** 内容摘要 */
    private String excerpt;
}
