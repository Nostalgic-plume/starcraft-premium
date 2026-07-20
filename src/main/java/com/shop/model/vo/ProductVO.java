package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品详情返回
 */
@Data
@Schema(description = "商品详情")
public class ProductVO {

    @Schema(description = "商品ID")
    private Long id;

    @Schema(description = "商品名称")
    private String name;

    @Schema(description = "商品描述")
    private String description;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "品牌")
    private String brand;

    @Schema(description = "主图")
    private String mainImage;

    @Schema(description = "轮播图")
    private List<String> images;

    @Schema(description = "详情")
    private String detail;

    @Schema(description = "最低价")
    private BigDecimal minPrice;

    @Schema(description = "最高价")
    private BigDecimal maxPrice;

    @Schema(description = "总库存")
    private Integer totalStock;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "SKU列表")
    private List<SkuVO> skuList;

    @Data
    @Schema(description = "SKU信息")
    public static class SkuVO {

        @Schema(description = "SKU ID")
        private Long id;

        @Schema(description = "SKU名称")
        private String name;

        @Schema(description = "规格值JSON")
        private String specs;

        @Schema(description = "价格")
        private BigDecimal price;

        @Schema(description = "原价")
        private BigDecimal originalPrice;

        @Schema(description = "库存")
        private Integer stock;

        @Schema(description = "图片")
        private String image;
    }
}
