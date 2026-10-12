package com.shop.rec.mall.model;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商城商品展示信息只读视图
 *
 * <p>
 * 榜单响应所需的展示字段：名称、主图与展示价格。
 * <b>价格取最低规格价格</b>——与商城列表页的展示口径保持一致，避免同一商品在不同位置显示不同价格。
 *
 * @author shop
 */
@Data
public class MallProductDisplayDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品标识 */
    private Long id;

    /** 商品名称 */
    private String name;

    /** 商品主图 */
    private String mainImage;

    /** 展示价格：最低规格价格 */
    private BigDecimal minPrice;
}
