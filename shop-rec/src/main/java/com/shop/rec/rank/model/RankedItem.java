package com.shop.rec.rank.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 榜单元素
 *
 * @author shop
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RankedItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 来源标记：固定坑位（响应字段约定） */
    public static final String SOURCE_FIXED_SLOT = "FIXED_SLOT";

    /** 商品标识 */
    private Long itemId;

    /** 召回来源标记：CATEGORY_PREF / HOT / FALLBACK / FIXED_SLOT */
    private String recallSource;

    /** 归一化总分，仅用于内部排查，前端不展示 */
    private double score;

    /** 是否为固定坑位商品（不参与个性化打分与重排） */
    private boolean fixedSlot;
}
