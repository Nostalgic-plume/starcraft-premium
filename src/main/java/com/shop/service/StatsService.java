package com.shop.service;

import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.SalesTrendVO;
import com.shop.model.vo.ProductSalesVO;

import java.util.List;
import java.util.Map;

/**
 * 统计报表服务接口
 *
 * @author shop
 */
public interface StatsService {

    /**
     * 控制台概览统计
     *
     * @return 控制台概览统计数据
     */
    DashboardStatsVO dashboard();

    /**
     * 销售额趋势
     *
     * @param days 天数（≤7 近7天，>7 近30天）
     * @return 按日聚合的销售额趋势列表
     */
    List<SalesTrendVO> salesTrend(int days);

    /**
     * 商品销量排行 TOP10
     *
     * @return 销量前 10 的商品排行列表
     */
    List<ProductSalesVO> productSales();

    /**
     * 用户增长趋势（近30天）
     *
     * @return 近 30 天每日新增用户趋势列表
     */
    List<SalesTrendVO> userGrowth();

    /**
     * 订单状态分布
     *
     * @return 各订单状态及其数量的 Map 列表
     */
    List<Map<String, Object>> orderDistribution();
}
