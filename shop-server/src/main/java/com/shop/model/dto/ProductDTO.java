package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新增/修改商品参数
 */
@Data
@Schema(description = "新增/修改商品参数")
public class ProductDTO {

    @NotBlank(message = "商品名称不能为空")
    @Schema(description = "商品名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "商品描述")
    private String description;

    @NotNull(message = "分类不能为空")
    @Schema(description = "分类ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long categoryId;

    @Schema(description = "品牌ID（关联 brand 表）")
    private Long brandId;

    @Schema(description = "主图URL")
    private String mainImage;

    @Schema(description = "轮播图列表")
    private List<String> images;

    @Schema(description = "详情内容（富文本）")
    private String detail;

    @NotEmpty(message = "SKU列表不能为空")
    @Valid
    @Schema(description = "SKU列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<SkuDTO> skuList;

    @Data
    @Schema(description = "SKU参数")
    public static class SkuDTO {

        /** SKU ID：仅编辑时由前端回传，用于标识要更新的已有 SKU；新增时留空走 INSERT */
        @Schema(description = "SKU ID（编辑时回传，新增时为空）")
        private Long id;

        @NotBlank(message = "SKU名称不能为空")
        private String name;

        @Schema(description = "规格值JSON")
        private String specs;

        @NotNull(message = "价格不能为空")
        @DecimalMin(value = "0.01", message = "价格必须大于 0")
        private BigDecimal price;

        @DecimalMin(value = "0", message = "原价不能为负")
        private BigDecimal originalPrice;

        @NotNull(message = "库存不能为空")
        @Min(value = 0, message = "库存不能为负")
        private Integer stock;

        private String image;
    }
}
