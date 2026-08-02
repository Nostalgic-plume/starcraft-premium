package com.shop.service.impl;

import com.shop.mapper.DwOverviewMapper;
import com.shop.model.vo.DashboardAllVO;
import com.shop.service.DashboardService;
import java.util.function.Supplier;
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

        vo.setOverview(safeQuery("ads_overview", () -> dwOverviewMapper.selectOverview()));
        if (vo.getOverview() == null) vo.setOverview(new DashboardAllVO.OverviewVO());

        vo.setGmvDaily(safeQuery("ads_gmv_daily", () -> dwOverviewMapper.selectGmvDaily()));
        if (vo.getGmvDaily() == null) vo.setGmvDaily(new DashboardAllVO.GmvDailyVO());

        vo.setOrderStats(safeQuery("ads_order_stats_daily", () -> dwOverviewMapper.selectOrderStats()));
        if (vo.getOrderStats() == null) vo.setOrderStats(new DashboardAllVO.OrderStatsVO());

        vo.setCategorySales(safeQuery("ads_category_sales", () -> dwOverviewMapper.selectCategorySales()));

        vo.setProductTop(safeQuery("ads_product_top50", () -> dwOverviewMapper.selectProductTop()));

        vo.setWeeklyTrend(safeQuery("ads_trend_weekly", () -> dwOverviewMapper.selectWeeklyTrend()));

        vo.setUserActive(safeQuery("ads_user_active", () -> dwOverviewMapper.selectUserActive()));
        if (vo.getUserActive() == null) vo.setUserActive(new DashboardAllVO.UserActiveVO());

        vo.setUserNewRetain(safeQuery("ads_user_new_retain", () -> dwOverviewMapper.selectUserNewRetain()));
        if (vo.getUserNewRetain() == null) vo.setUserNewRetain(new DashboardAllVO.UserNewRetainVO());

        vo.setShopDaily(safeQuery("ads_shop_daily", () -> dwOverviewMapper.selectShopDaily()));
        if (vo.getShopDaily() == null) vo.setShopDaily(new DashboardAllVO.ShopDailyVO());

        vo.setGmvTrend(safeQuery("GMV 日趋势", () -> dwOverviewMapper.selectGmvTrend()));

        vo.setOrderTrend(safeQuery("订单状态日趋势", () -> dwOverviewMapper.selectOrderTrend()));

        log.info("数据看板查询完成");
        return vo;
    }

    private <T> T safeQuery(String logPrefix, Supplier<T> query) {
        try {
            return query.get();
        } catch (Exception e) {
            log.warn("查询 {} 异常: {}", logPrefix, e.getMessage());
            return null;
        }
    }
}
