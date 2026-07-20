package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.Result;
import com.shop.constant.PermissionConstants;
import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.SalesTrendVO;
import com.shop.model.vo.ProductSalesVO;
import com.shop.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理后台统计接口
 * 提供控制台仪表盘所需的各项统计数据
 *
 * @author shop
 */
@Tag(name = "统计模块")
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final StatsService statsService;

    @SaCheckPermission(PermissionConstants.STATS_VIEW)
    @GetMapping("/dashboard")
    @Operation(summary = "控制台概览统计")
    public Result<DashboardStatsVO> dashboard() {
        return Result.success(statsService.dashboard());
    }

    @SaCheckPermission(PermissionConstants.STATS_VIEW)
    @GetMapping("/sales-trend")
    @Operation(summary = "销售额趋势")
    public Result<List<SalesTrendVO>> salesTrend(@RequestParam(defaultValue = "7") int days) {
        return Result.success(statsService.salesTrend(days));
    }

    @SaCheckPermission(PermissionConstants.STATS_VIEW)
    @GetMapping("/product-sales")
    @Operation(summary = "商品销量排行 TOP10")
    public Result<List<ProductSalesVO>> productSales() {
        return Result.success(statsService.productSales());
    }

    @SaCheckPermission(PermissionConstants.STATS_VIEW)
    @GetMapping("/user-growth")
    @Operation(summary = "用户增长趋势（近30天）")
    public Result<List<SalesTrendVO>> userGrowth() {
        return Result.success(statsService.userGrowth());
    }

    @SaCheckPermission(PermissionConstants.STATS_VIEW)
    @GetMapping("/order-distribution")
    @Operation(summary = "订单状态分布")
    public Result<List<Map<String, Object>>> orderDistribution() {
        return Result.success(statsService.orderDistribution());
    }
}
