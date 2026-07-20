package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 购物车操作参数
 */
@Data
@Schema(description = "购物车操作参数")
public class CartDTO {

    @NotNull(message = "商品不能为空")
    @Schema(description = "SKU ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long skuId;

    @Min(value = 1, message = "数量至少为1")
    @Schema(description = "数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer quantity;
}
