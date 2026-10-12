package com.shop.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 商品分页查询参数
 * 支持按分类、关键字筛选及排序，page 和 pageSize 有默认值
 */
@Data
@Schema(description = "商品分页查询")
public class ProductPageQuery {

    @Schema(description = "页码", example = "1")
    private Integer page = 1;

    @Schema(description = "每页条数", example = "10")
    private Integer pageSize = 10;

    @Schema(description = "分类ID（二级分类筛选）")
    private Long categoryId;

    @Schema(description = "一级分类ID（查询该一级分类下所有二级分类的商品）")
    private Long firstCategoryId;

    @Schema(description = "商家ID（管理员查询指定商家商品）")
    private Long shopId;

    @Schema(description = "关键字（搜索商品名称）")
    private String keyword;

    @Schema(description = "商品状态：0=待审核 1=上架 2=下架。仅管理端/商家端生效；用户端强制仅返回上架商品")
    private Integer status;

    @Schema(description = "排序：price_asc/price_desc/time_desc")
    private String sort;
}
