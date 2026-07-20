package com.shop.service.impl;

import com.shop.mapper.DwOverviewMapper;
import com.shop.model.vo.DashboardAllVO;
import com.shop.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 数据看板查询服务实现
 * 聚合离线数仓查询结果，若某模块无数据则返回空对象而非 null
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DwOverviewMapper dwOverviewMapper;

    @Override
    public DashboardAllVO getDashboardAll() {
        DashboardAllVO vo = new DashboardAllVO();

        try {
            vo.setOverview(dwOverviewMapper.selectOverview());
        } catch (Exception e) {
            log.warn("查询 ads_overview 异常: {}", e.getMessage());
        }
        if (vo.getOverview() == null) {
            vo.setOverview(new DashboardAllVO.OverviewVO());
        }

        try {
            vo.setGmvDaily(dwOverviewMapper.selectGmvDaily());
        } catch (Exception e) {
            log.warn("查询 ads_gmv_daily 异常: {}", e.getMessage());
        }
        if (vo.getGmvDaily() == null) {
            vo.setGmvDaily(new DashboardAllVO.GmvDailyVO());
        }

        try {
            vo.setOrderStats(dwOverviewMapper.selectOrderStats());
        } catch (Exception e) {
            log.warn("查询 ads_order_stats_daily 异常: {}", e.getMessage());
        }
        if (vo.getOrderStats() == null) {
            vo.setOrderStats(new DashboardAllVO.OrderStatsVO());
        }

        try {
            vo.setCategorySales(dwOverviewMapper.selectCategorySales());
        } catch (Exception e) {
            log.warn("查询 ads_category_sales 异常: {}", e.getMessage());
        }

        try {
            vo.setProductTop(dwOverviewMapper.selectProductTop());
        } catch (Exception e) {
            log.warn("查询 ads_product_top50 异常: {}", e.getMessage());
        }

        try {
            vo.setWeeklyTrend(dwOverviewMapper.selectWeeklyTrend());
        } catch (Exception e) {
            log.warn("查询 ads_trend_weekly 异常: {}", e.getMessage());
        }

        try {
            vo.setUserActive(dwOverviewMapper.selectUserActive());
        } catch (Exception e) {
            log.warn("查询 ads_user_active 异常: {}", e.getMessage());
        }
        if (vo.getUserActive() == null) {
            vo.setUserActive(new DashboardAllVO.UserActiveVO());
        }

        try {
            vo.setUserNewRetain(dwOverviewMapper.selectUserNewRetain());
        } catch (Exception e) {
            log.warn("查询 ads_user_new_retain 异常: {}", e.getMessage());
        }
        if (vo.getUserNewRetain() == null) {
            vo.setUserNewRetain(new DashboardAllVO.UserNewRetainVO());
        }

        try {
            vo.setShopDaily(dwOverviewMapper.selectShopDaily());
        } catch (Exception e) {
            log.warn("查询 ads_shop_daily 异常: {}", e.getMessage());
        }
        if (vo.getShopDaily() == null) {
            vo.setShopDaily(new DashboardAllVO.ShopDailyVO());
        }

        try {
            vo.setGmvTrend(dwOverviewMapper.selectGmvTrend());
        } catch (Exception e) {
            log.warn("查询 GMV 日趋势异常: {}", e.getMessage());
        }

        try {
            vo.setOrderTrend(dwOverviewMapper.selectOrderTrend());
        } catch (Exception e) {
            log.warn("查询订单状态日趋势异常: {}", e.getMessage());
        }

        log.info("数据看板查询完成");
        return vo;
    }
}
