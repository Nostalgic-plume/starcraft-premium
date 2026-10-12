package com.shop.service.impl;

import com.shop.mapper.StatsMapper;
import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.ProductSalesVO;
import com.shop.model.vo.SalesTrendVO;
import com.shop.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 统计报表服务实现
 *
 * @author shop
 */
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final StatsMapper statsMapper;

    /**
     * 统计报表概览：聚合总用户数、总商品数、总订单数、总销售额与待处理订单数
     *
     * @return 概览统计 DashboardStatsVO
     */
    @Override
    public DashboardStatsVO dashboard() {
        DashboardStatsVO vo = new DashboardStatsVO();
        vo.setTotalUsers(statsMapper.totalUsers());
        vo.setTotalProducts(statsMapper.totalProducts());
        vo.setTotalOrders(statsMapper.totalOrders());
        vo.setTotalSales(statsMapper.totalSales());
        vo.setPendingOrders(statsMapper.pendingOrders());
        return vo;
    }

    /**
     * 查询销售额趋势：days &gt; 7 查近 30 天数据，否则查近 7 天数据
     *
     * @param days 趋势天数（&lt;=7 返回近7天，&gt;7 返回近30天）
     * @return 销售额趋势列表 {@code List<SalesTrendVO>}
     */
    @Override
    public List<SalesTrendVO> salesTrend(int days) {
        return days > 7
                ? statsMapper.salesTrendLast30Days()
                : statsMapper.salesTrendLast7Days();
    }

    /**
     * 查询商品销量排行（按销量倒序）
     *
     * @return 商品销量排行列表 {@code List<ProductSalesVO>}
     */
    @Override
    public List<ProductSalesVO> productSales() {
        return statsMapper.topProductSales();
    }

    /**
     * 查询用户增长趋势（近 30 天每日新增用户数）
     *
     * @return 用户增长趋势列表 {@code List<SalesTrendVO>}
     */
    @Override
    public List<SalesTrendVO> userGrowth() {
        return statsMapper.userGrowthLast30Days();
    }

    /**
     * 查询订单状态分布（各状态对应的订单数量）
     *
     * @return 订单状态分布列表 {@code List<Map<String, Object>>}
     */
    @Override
    public List<Map<String, Object>> orderDistribution() {
        return statsMapper.orderStatusDistribution();
    }
}
