package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 * 对应数据库 orders 表，记录订单完整信息及收货地址快照
 * 订单状态流转：待支付 → 已支付 → 已发货 → 已收货 → 已完成
 *                            ↻ 已取消（仅待支付可取消）
 */
@Data
@TableName("orders")
@Schema(description = "订单")
public class OrderDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "商家ID（关联商家用户表）")
    private Long shopId;

    @Schema(description = "订单金额")
    private BigDecimal totalAmount;

    @Schema(description = "实际支付金额")
    private BigDecimal actualAmount;

    @Schema(description = "优惠金额")
    private BigDecimal discountAmount;

    @Schema(description = "运费")
    private BigDecimal freight;

    @Schema(description = "状态：0待支付 1已支付 2已发货 3已收货 4已完成 5已取消 6已退款")
    private Integer status;

    @Schema(description = "收货人")
    private String receiverName;

    @Schema(description = "收货电话")
    private String receiverPhone;

    @Schema(description = "收货地址")
    private String receiverAddress;

    @Schema(description = "支付方式：1微信 2支付宝")
    private Integer payType;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    @Schema(description = "发货时间")
    private LocalDateTime deliverTime;

    @Schema(description = "物流单号")
    private String logisticsNo;

    @Schema(description = "收货时间")
    private LocalDateTime receiveTime;

    @Schema(description = "订单备注")
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
