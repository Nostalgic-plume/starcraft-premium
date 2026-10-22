package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情返回
 */
@Data
@Schema(description = "订单详情")
public class OrderVO {

    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "商家ID")
    private Long shopId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "订单金额")
    private BigDecimal totalAmount;

    @Schema(description = "实际支付")
    private BigDecimal actualAmount;

    @Schema(description = "优惠金额")
    private BigDecimal discountAmount;

    @Schema(description = "运费")
    private BigDecimal freight;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "状态文字")
    private String statusText;

    @Schema(description = "收货人")
    private String receiverName;

    @Schema(description = "收货电话")
    private String receiverPhone;

    @Schema(description = "收货地址")
    private String receiverAddress;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    @Schema(description = "发货时间")
    private LocalDateTime deliverTime;

    @Schema(description = "收货时间")
    private LocalDateTime receiveTime;

    @Schema(description = "物流单号")
    private String logisticsNo;

    @Schema(description = "订单备注")
    private String remark;

    @Schema(description = "商品列表")
    private List<OrderItemVO> items;

    @Data
    @Schema(description = "订单商品")
    public static class OrderItemVO {

        @Schema(description = "订单项 ID（评价的唯一业务键）")
        private Long id;

        @Schema(description = "商品 ID")
        private Long productId;

        @Schema(description = "SKU ID")
        private Long skuId;

        @Schema(description = "商品名称")
        private String productName;

        @Schema(description = "SKU名称")
        private String skuName;

        @Schema(description = "商品主图")
        private String productImage;

        @Schema(description = "单价")
        private BigDecimal price;

        @Schema(description = "数量")
        private Integer quantity;

        @Schema(description = "小计")
        private BigDecimal subtotal;
    }
}
