package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单商品项实体
 * 记录下单时商品快照（名称、价格、数量），确保历史订单不受商品信息变更影响
 */
@Data
@TableName("order_item")
@Schema(description = "订单商品项")
public class OrderItemDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "商品名称")
    private String productName;

    @Schema(description = "SKU ID")
    private Long skuId;

    @Schema(description = "SKU名称")
    private String skuName;

    @Schema(description = "商品主图快照（下单时的主图URL）")
    @JsonProperty("productImage")
    private String productMainImage;

    @Schema(description = "单价")
    private BigDecimal price;

    @Schema(description = "数量")
    private Integer quantity;

    @Schema(description = "小计")
    private BigDecimal subtotal;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
