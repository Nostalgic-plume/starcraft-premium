package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 创建订单参数
 */
@Data
@Schema(description = "创建订单参数")
public class OrderCreateDTO {

    @NotBlank(message = "防重令牌不能为空")
    @Schema(description = "防重令牌（下单前从接口获取）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String token;

    @Schema(description = "收货地址ID")
    private Long addressId;

    @Schema(description = "收货人")
    private String receiverName;

    @Schema(description = "收货电话")
    private String receiverPhone;

    @Schema(description = "收货地址")
    private String receiverAddress;

    @Schema(description = "订单备注")
    private String remark;

    @NotEmpty(message = "商品不能为空")
    @Valid
    @Schema(description = "商品列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<OrderItemDTO> items;

    @Data
    @Schema(description = "下单商品")
    public static class OrderItemDTO {

        @NotNull(message = "SKU ID不能为空")
        private Long skuId;

        @NotNull(message = "数量不能为空")
        private Integer quantity;
    }
}
