package com.shop.service;

import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.SalesTrendVO;
import com.shop.model.vo.ProductSalesVO;

import java.util.List;
import java.util.Map;

/**
 * 统计报表服务接口
 */
public interface StatsService {

    /**
     * 控制台概览统计
     */
    DashboardStatsVO dashboard();

    /**
     * 销售额趋势
     *
     * @param days 天数（≤7 近7天，>7 近30天）
     */
    List<SalesTrendVO> salesTrend(int days);

    /**
     * 商品销量排行 TOP10
     */
    List<ProductSalesVO> productSales();

    /**
     * 用户增长趋势（近30天）
     */
    List<SalesTrendVO> userGrowth();

    /**
     * 订单状态分布
     */
    List<Map<String, Object>> orderDistribution();
}
