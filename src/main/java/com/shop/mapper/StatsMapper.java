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

    /**
     * 统计平台用户总数（仅统计逻辑未删除的用户）
     *
     * @return 用户总数
     */
    @Select("SELECT COUNT(*) FROM `user` WHERE deleted = 0")
    Long totalUsers();

    /**
     * 统计平台商品总数（仅统计逻辑未删除的商品）
     *
     * @return 商品总数
     */
    @Select("SELECT COUNT(*) FROM `product` WHERE deleted = 0")
    Long totalProducts();

    /**
     * 统计平台订单总数（仅统计逻辑未删除的订单）
     *
     * @return 订单总数
     */
    @Select("SELECT COUNT(*) FROM `orders` WHERE deleted = 0")
    Long totalOrders();

    /**
     * 统计平台销售总额（GMV，仅统计状态为已支付/已完成的有效订单）
     *
     * @return 销售总额，无有效订单时返回 0
     */
    @Select("SELECT COALESCE(SUM(actual_amount), 0) FROM `orders` WHERE deleted = 0 AND status IN (1,2,3,4)")
    Double totalSales();

    /**
     * 统计待处理订单数（status = 0，即待支付/待确认状态）
     *
     * @return 待处理订单数
     */
    @Select("SELECT COUNT(*) FROM `orders` WHERE deleted = 0 AND status = 0")
    Long pendingOrders();

    /**
     * 近 7 天每日销售趋势（按订单创建日期分组统计有效订单金额）
     *
     * @return 每日销售额列表（按日期升序，无订单的日期不出现）
     */
    @Select("SELECT DATE(create_time) AS `date`, COALESCE(SUM(actual_amount), 0) AS amount " +
            "FROM `orders` WHERE deleted = 0 AND status IN (1,2,3,4) " +
            "AND create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY `date`")
    List<SalesTrendVO> salesTrendLast7Days();

    /**
     * 近 30 天每日销售趋势（按订单创建日期分组统计有效订单金额）
     *
     * @return 每日销售额列表（按日期升序，无订单的日期不出现）
     */
    @Select("SELECT DATE(create_time) AS `date`, COALESCE(SUM(actual_amount), 0) AS amount " +
            "FROM `orders` WHERE deleted = 0 AND status IN (1,2,3,4) " +
            "AND create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY `date`")
    List<SalesTrendVO> salesTrendLast30Days();

    /**
     * 商品销量排行 TOP10（按销量倒序）
     *
     * @return 商品销量排行列表（商品名 + 累计销量，最多 10 条）
     */
    @Select("SELECT product_name AS name, SUM(quantity) AS totalSales " +
            "FROM `order_item` WHERE deleted = 0 GROUP BY product_name ORDER BY totalSales DESC LIMIT 10")
    List<ProductSalesVO> topProductSales();

    /**
     * 近 30 天每日用户新增数（按注册日期分组统计逻辑未删除的用户）
     *
     * @return 每日新增用户列表（按日期升序）
     */
    @Select("SELECT DATE(create_time) AS `date`, COUNT(*) AS cnt " +
            "FROM `user` WHERE deleted = 0 " +
            "AND create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY `date`")
    List<SalesTrendVO> userGrowthLast30Days();

    /**
     * 订单状态分布统计（各状态下订单数量）
     *
     * @return 列表，每项为 {status: 订单状态编码, cnt: 该状态订单数}，按状态升序
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM `orders` " +
            "WHERE deleted = 0 GROUP BY status ORDER BY status")
    List<Map<String, Object>> orderStatusDistribution();

    // ==================== 商家看板 ====================

    /**
     * 商家看板聚合数据：本店商品数、本店订单数、本店销售额、本店待处理订单数
     * 单条 SQL 一次性聚合，totalUsers 恒为 0（商家端无用户统计）
     *
     * @param shopId 商家ID（商家登录会话自动注入，无此数据不返回）
     * @return 商家看板统计 VO
     */
    @Select("SELECT " +
            "  (SELECT COUNT(*) FROM `product` WHERE deleted = 0 AND shop_id = #{shopId}) AS totalProducts, " +
            "  0 AS totalUsers, " +
            "  COALESCE(COUNT(*), 0) AS totalOrders, " +
            "  COALESCE(SUM(CASE WHEN status IN (1,2,3,4) THEN actual_amount ELSE 0 END), 0) AS totalSales, " +
            "  COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS pendingOrders " +
            "FROM `orders` WHERE deleted = 0 AND shop_id = #{shopId}")
    DashboardStatsVO selectMerchantDashboard(Long shopId);
}
