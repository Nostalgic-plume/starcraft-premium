package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 控制台统计数据 VO
 *
 * @author shop
 */
@Data
@Schema(description = "控制台统计概览")
public class DashboardStatsVO {

    @Schema(description = "用户总数")
    private Long totalUsers;

    @Schema(description = "商品总数")
    private Long totalProducts;

    @Schema(description = "订单总数")
    private Long totalOrders;

    @Schema(description = "销售总额")
    private Double totalSales;

    @Schema(description = "待处理订单数")
    private Long pendingOrders;
}
