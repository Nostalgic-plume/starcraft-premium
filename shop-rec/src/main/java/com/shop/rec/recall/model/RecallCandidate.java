package com.shop.rec.recall.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单条召回候选
 *
 * <p>
 * 多路召回的统一载体，携带来源标记以便融合环节记录来源集合、以及排查与埋点统计
 * （来源标记优先级：固定坑位 &gt; 类目偏好 &gt; 热门 &gt; 兜底，见详细设计 6.3）。
 *
 * @author shop
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecallCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 来源标记：类目偏好召回 */
    public static final String SOURCE_CATEGORY_PREF = "CATEGORY_PREF";

    /** 来源标记：热门召回 */
    public static final String SOURCE_HOT = "HOT";

    /** 来源标记：兜底名单 */
    public static final String SOURCE_FALLBACK = "FALLBACK";

    /** 商品标识 */
    private Long itemId;

    /** 召回来源标记 */
    private String source;

    /** 召回分：类目偏好路为类目内热度分，热门路为全局热度分 */
    private double recallScore;

    /** 所属二级类目（类目偏好路携带，供打分环节取类目偏好分） */
    private Long categoryId;
}
