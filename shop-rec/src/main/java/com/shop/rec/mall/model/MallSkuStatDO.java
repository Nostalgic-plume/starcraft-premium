package com.shop.rec.mall.model;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商城商品规格统计视图
 *
 * <p>
 * 商品维度的库存与价格汇总：总库存用于可售判定，最低价用于榜单展示。
 *
 * @author shop
 */
@Data
public class MallSkuStatDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品标识 */
    private Long productId;

    /** 全部规格的库存合计 */
    private Integer totalStock;

    /** 最低规格价格 */
    private BigDecimal minPrice;
}
