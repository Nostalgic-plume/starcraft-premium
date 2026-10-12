package com.shop.rec.fusion.model;

import lombok.Data;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * 召回候选池元素（融合去重与过滤后）
 *
 * <p>
 * 同一商品命中多路时只保留一条记录，并记录来源集合用于排查与埋点统计
 * （来源标记优先级：固定坑位 &gt; 类目偏好 &gt; 热门 &gt; 兜底，见详细设计 6.3）。
 *
 * @author shop
 */
@Data
public class FilteredCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品标识 */
    private Long itemId;

    /** 命中该商品的召回来源集合 */
    private Set<String> sources = new HashSet<>();

    /** 归一化召回分：同一商品命中多路时取各路归一化分的较大值 */
    private double normalizedScore;

    /** 所属二级类目（类目偏好路携带，供打分环节取类目偏好分） */
    private Long categoryId;

    /**
     * 品牌标识（过滤环节读取商品状态缓存时顺带保留）
     *
     * <p>供打分环节取品牌偏好分，避免为取该字段再次访问内存库。
     */
    private Long brandId;
}
