package com.shop.model.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据看板全量 VO
 * 对应 DataDashboard.vue 中 fetchDashboardAll 的返回结构
 *
 * @author shop
 */
@Data
@Accessors(chain = true)
public class DashboardAllVO {

    /** 顶部概览卡片 */
    private OverviewVO overview;
    /** GMV 概览 */
    private GmvDailyVO gmvDaily;
    /** 订单状态分布 */
    private OrderStatsVO orderStats;
    /** 分类销售排行 */
    private List<CategorySalesVO> categorySales;
    /** 商品销售排行 TOP */
    private List<ProductTopVO> productTop;
    /** 周趋势 */
    private List<WeeklyTrendVO> weeklyTrend;
    /** 用户活跃 */
    private UserActiveVO userActive;
    /** 用户新增 / 留存 */
    private UserNewRetainVO userNewRetain;
    /** 店铺日报 */
    private ShopDailyVO shopDaily;
    /** GMV 日趋势（多天） */
    private List<GmvTrendPointVO> gmvTrend;
    /** 订单状态日趋势（多天） */
    private List<OrderTrendPointVO> orderTrend;

    // ========== 内部 VO ==========

    @Data
    @Accessors(chain = true)
    public static class OverviewVO {
        private String dt;
        private Integer totalUsers;
        private Integer totalProducts;
        private Integer totalOrders;
        private BigDecimal totalGmv;
        private BigDecimal avgOrderPrice;
    }

    @Data
    @Accessors(chain = true)
    public static class GmvDailyVO {
        private String dt;
        private BigDecimal gmv;
        private Integer orderCount;
        private Integer paidOrderCount;
        private Integer paidUserCount;
        private BigDecimal avgOrderAmount;
    }

    @Data
    @Accessors(chain = true)
    public static class OrderStatsVO {
        private String dt;
        private Integer totalOrders;
        private Integer paidOrders;
        private Integer shippedOrders;
        private Integer completedOrders;
        private Integer cancelledOrders;
        private Integer refundOrders;
        private BigDecimal conversionRate;
    }

    @Data
    @Accessors(chain = true)
    public static class CategorySalesVO {
        private String categoryName;
        private BigDecimal saleAmount;
        private Integer saleCount;
    }

    @Data
    @Accessors(chain = true)
    public static class ProductTopVO {
        private String productName;
        private BigDecimal saleAmount;
        private Integer saleQuantity;
    }

    @Data
    @Accessors(chain = true)
    public static class WeeklyTrendVO {
        private String weekStart;
        private String weekEnd;
        private BigDecimal gmv;
        private Integer orderCount;
        private Integer userCount;
        private BigDecimal gmvMom;
    }

    @Data
    @Accessors(chain = true)
    public static class UserActiveVO {
        private String dt;
        private Integer dau;
        private Integer orderUserCount;
    }

    @Data
    @Accessors(chain = true)
    public static class UserNewRetainVO {
        private String dt;
        private Integer newUsers;
        private Integer day1RetainUsers;
        private BigDecimal day1RetainRate;
        private Integer day7RetainUsers;
        private BigDecimal day7RetainRate;
        private Integer day30RetainUsers;
        private BigDecimal day30RetainRate;
    }

    @Data
    @Accessors(chain = true)
    public static class ShopDailyVO {
        private String dt;
        private String shopName;
        private BigDecimal gmv;
        private Integer orderCount;
        private Integer productCount;
    }

    // ========== 趋势内部 VO ==========

    @Data
    @Accessors(chain = true)
    public static class GmvTrendPointVO {
        private String dt;
        private BigDecimal gmv;
        private Integer orderCount;
        private Integer paidOrderCount;
        private Integer paidUserCount;
    }

    @Data
    @Accessors(chain = true)
    public static class OrderTrendPointVO {
        private String dt;
        private Integer totalOrders;
        private Integer paidOrders;
        private Integer shippedOrders;
        private Integer completedOrders;
        private Integer cancelledOrders;
    }
}
