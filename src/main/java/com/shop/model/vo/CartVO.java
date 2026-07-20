package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车返回
 */
@Data
@Schema(description = "购物车商品")
public class CartVO {

    @Schema(description = "购物车ID")
    private Long id;

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "商品名称")
    private String productName;

    @Schema(description = "商品主图")
    private String productImage;

    @Schema(description = "SKU ID")
    private Long skuId;

    @Schema(description = "SKU名称")
    private String skuName;

    @Schema(description = "价格")
    private BigDecimal price;

    @Schema(description = "数量")
    private Integer quantity;

    @Schema(description = "小计")
    private BigDecimal subtotal;

    @Schema(description = "可用库存")
    private Integer stock;

    @Schema(description = "是否选中")
    private Boolean checked;

    @Schema(description = "是否已下单")
    private Integer isOrdered;

    @Schema(description = "是否有效（库存不足/下架时为false）")
    private Boolean valid;
}
