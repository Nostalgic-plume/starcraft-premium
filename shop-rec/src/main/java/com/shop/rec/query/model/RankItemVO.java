package com.shop.rec.query.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 榜单商品项（外部接口 E-02 的响应元素）
 *
 * <p>
 * 展示字段取自商城商品数据；推荐侧只读不写（见概要设计 3.2）。
 *
 * @author shop
 */
@Data
@Schema(description = "榜单商品项")
public class RankItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "商品标识")
    private Long itemId;

    @Schema(description = "商品名称")
    private String itemName;

    @Schema(description = "商品主图地址")
    private String mainImage;

    @Schema(description = "展示价格，取最低规格价格")
    private BigDecimal price;

    @Schema(description = "召回来源：CATEGORY_PREF / HOT / FALLBACK / FIXED_SLOT")
    private String recallSource;

    @Schema(description = "归一化总分，仅用于内部排查，前端不展示")
    private double score;
}
