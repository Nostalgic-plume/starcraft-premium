package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 商品销量 VO
 *
 * @author shop
 */
@Data
@Schema(description = "商品销量排行")
public class ProductSalesVO {

    @Schema(description = "商品名称")
    private String name;

    @Schema(description = "销量")
    private Long totalSales;
}
