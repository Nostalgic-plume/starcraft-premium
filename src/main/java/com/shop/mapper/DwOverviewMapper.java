package com.shop.mapper;

import com.shop.model.vo.DashboardAllVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 离线数仓（shop_ads）查询 Mapper
 * 使用全限定表名跨库查询 shop_ads 数据库
 *
 * @author shop
 */
@Mapper
public interface DwOverviewMapper {

    /**
     * 最新快照概览（ads_overview）
     */
    @Select("SELECT dt, total_users, total_products, total_orders, total_gmv, avg_order_price " +
            "FROM shop_ads.ads_overview ORDER BY dt DESC LIMIT 1")
    DashboardAllVO.OverviewVO selectOverview();

    /**
     * 最新 GMV 日报（ads_gmv_daily）
     */
    @Select("SELECT dt, gmv, order_count, paid_order_count, paid_user_count, avg_order_amount " +
            "FROM shop_ads.ads_gmv_daily ORDER BY dt DESC LIMIT 1")
    DashboardAllVO.GmvDailyVO selectGmvDaily();

    /**
     * 最新订单状态分布（ads_order_stats_daily）
     */
    @Select("SELECT dt, total_orders, paid_orders, shipped_orders, completed_orders, cancelled_orders, refund_orders, conversion_rate " +
            "FROM shop_ads.ads_order_stats_daily ORDER BY dt DESC LIMIT 1")
    DashboardAllVO.OrderStatsVO selectOrderStats();

    /**
     * 分类销售排行（ads_category_sales）
     */
    @Select("SELECT category_name, sale_amount, sale_count " +
            "FROM shop_ads.ads_category_sales ORDER BY sale_amount DESC")
    List<DashboardAllVO.CategorySalesVO> selectCategorySales();

    /**
     * 商品销售排行 TOP50（ads_product_top50）
     */
    @Select("SELECT product_name, sale_amount, sale_quantity " +
            "FROM shop_ads.ads_product_top50 ORDER BY sale_amount DESC")
    List<DashboardAllVO.ProductTopVO> selectProductTop();

    /**
     * 周趋势（ads_trend_weekly）
     */
    @Select("SELECT week_start, week_end, gmv, order_count, user_count, gmv_mom " +
            "FROM shop_ads.ads_trend_weekly ORDER BY week_start ASC")
    List<DashboardAllVO.WeeklyTrendVO> selectWeeklyTrend();

    /**
     * GMV 日趋势（ads_gmv_daily 按日期升序）
     */
    @Select("SELECT dt, gmv, order_count, paid_order_count, paid_user_count " +
            "FROM shop_ads.ads_gmv_daily ORDER BY dt ASC")
    List<DashboardAllVO.GmvTrendPointVO> selectGmvTrend();

    /**
     * 订单状态日趋势（ads_order_stats_daily 按日期升序）
     */
    @Select("SELECT dt, total_orders, paid_orders, shipped_orders, completed_orders, cancelled_orders " +
            "FROM shop_ads.ads_order_stats_daily ORDER BY dt ASC")
    List<DashboardAllVO.OrderTrendPointVO> selectOrderTrend();

    /**
     * 用户活跃（ads_user_active）
     */
    @Select("SELECT dt, dau, order_user_count " +
            "FROM shop_ads.ads_user_active ORDER BY dt DESC LIMIT 1")
    DashboardAllVO.UserActiveVO selectUserActive();

    /**
     * 用户新增/留存（ads_user_new_retain）
     */
    @Select("SELECT dt, new_users, day1_retain_users, day1_retain_rate, " +
            "day7_retain_users, day7_retain_rate, " +
            "day30_retain_users, day30_retain_rate " +
            "FROM shop_ads.ads_user_new_retain ORDER BY dt DESC LIMIT 1")
    DashboardAllVO.UserNewRetainVO selectUserNewRetain();

    /**
     * 店铺日报（ads_shop_daily）
     */
    @Select("SELECT dt, shop_name, gmv, order_count, product_count " +
            "FROM shop_ads.ads_shop_daily ORDER BY dt DESC LIMIT 1")
    DashboardAllVO.ShopDailyVO selectShopDaily();
}
