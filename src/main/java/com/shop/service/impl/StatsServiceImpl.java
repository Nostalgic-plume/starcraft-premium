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
 */
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final StatsMapper statsMapper;

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

    @Override
    public List<SalesTrendVO> salesTrend(int days) {
        return days > 7
                ? statsMapper.salesTrendLast30Days()
                : statsMapper.salesTrendLast7Days();
    }

    @Override
    public List<ProductSalesVO> productSales() {
        return statsMapper.topProductSales();
    }

    @Override
    public List<SalesTrendVO> userGrowth() {
        return statsMapper.userGrowthLast30Days();
    }

    @Override
    public List<Map<String, Object>> orderDistribution() {
        return statsMapper.orderStatusDistribution();
    }
}
