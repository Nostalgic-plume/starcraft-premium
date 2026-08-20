package com.shop.mapper;

import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.SalesTrendVO;
import com.shop.model.vo.ProductSalesVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 统计报表 Mapper
 * 提供控制台数据统计所需的复杂查询
 *
 * @author shop
 */
@Mapper
public interface StatsMapper {

    @Select("SELECT COUNT(*) FROM `user` WHERE deleted = 0")
    Long totalUsers();

    @Select("SELECT COUNT(*) FROM `product` WHERE deleted = 0")
    Long totalProducts();

    @Select("SELECT COUNT(*) FROM `orders` WHERE deleted = 0")
    Long totalOrders();

    @Select("SELECT COALESCE(SUM(actual_amount), 0) FROM `orders` WHERE deleted = 0 AND status IN (1,2,3,4)")
    Double totalSales();

    @Select("SELECT COUNT(*) FROM `orders` WHERE deleted = 0 AND status = 0")
    Long pendingOrders();

    @Select("SELECT DATE(create_time) AS `date`, COALESCE(SUM(actual_amount), 0) AS amount " +
            "FROM `orders` WHERE deleted = 0 AND status IN (1,2,3,4) " +
            "AND create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY `date`")
    List<SalesTrendVO> salesTrendLast7Days();

    @Select("SELECT DATE(create_time) AS `date`, COALESCE(SUM(actual_amount), 0) AS amount " +
            "FROM `orders` WHERE deleted = 0 AND status IN (1,2,3,4) " +
            "AND create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY `date`")
    List<SalesTrendVO> salesTrendLast30Days();

    @Select("SELECT product_name AS name, SUM(quantity) AS totalSales " +
            "FROM `order_item` WHERE deleted = 0 GROUP BY product_name ORDER BY totalSales DESC LIMIT 10")
    List<ProductSalesVO> topProductSales();

    @Select("SELECT DATE(create_time) AS `date`, COUNT(*) AS cnt " +
            "FROM `user` WHERE deleted = 0 " +
            "AND create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY `date`")
    List<SalesTrendVO> userGrowthLast30Days();

    @Select("SELECT status, COUNT(*) AS cnt FROM `orders` " +
            "WHERE deleted = 0 GROUP BY status ORDER BY status")
    List<Map<String, Object>> orderStatusDistribution();

    // ==================== 商家看板 ====================

    @Select("SELECT " +
            "  (SELECT COUNT(*) FROM `product` WHERE deleted = 0 AND shop_id = #{shopId}) AS totalProducts, " +
            "  0 AS totalUsers, " +
            "  COALESCE(COUNT(*), 0) AS totalOrders, " +
            "  COALESCE(SUM(CASE WHEN status IN (1,2,3,4) THEN actual_amount ELSE 0 END), 0) AS totalSales, " +
            "  COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS pendingOrders " +
            "FROM `orders` WHERE deleted = 0 AND shop_id = #{shopId}")
    DashboardStatsVO selectMerchantDashboard(Long shopId);
}
