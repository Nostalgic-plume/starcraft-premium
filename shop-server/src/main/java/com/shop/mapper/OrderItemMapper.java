package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.dto.ProductSalesDTO;
import com.shop.model.entity.OrderItemDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 订单商品项 Mapper
 * 提供订单商品项表（order_item）的基础 CRUD
 *
 * @author shop
 */
public interface OrderItemMapper extends BaseMapper<OrderItemDO> {

    /**
     * 统计商品累计销量（已完成订单的有效销量）
     */
    @Select("SELECT COALESCE(SUM(oi.quantity), 0) FROM order_item oi " +
            "JOIN `orders` o ON oi.order_id = o.id " +
            "WHERE oi.product_id = #{productId} AND o.status IN (1,2,3,4)")
    Integer sumQuantityByProductId(@Param("productId") Long productId);

    /**
     * 批量统计多个商品的累计销量（口径与 {@link #sumQuantityByProductId(Long)} 一致）
     * <p>
     * 商品列表页需要整页销量，逐条调用会产生 N 次查询（pageSize 上限 200 → 最多 200 次），
     * 故提供 GROUP BY 的批量版本。查询走 idx_product_id 索引。
     *
     * @param productIds 商品 ID 集合（调用方保证非空，空集合会导致 SQL 语法错误）
     * @return 各商品销量；**无销量的商品不会出现在结果中**，调用方按 0 处理
     */
    @Select("<script>" +
            "SELECT oi.product_id AS productId, CAST(COALESCE(SUM(oi.quantity), 0) AS SIGNED) AS total " +
            "FROM order_item oi " +
            "JOIN `orders` o ON oi.order_id = o.id " +
            "WHERE o.status IN (1,2,3,4) AND oi.product_id IN " +
            "<foreach collection='productIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY oi.product_id" +
            "</script>")
    List<ProductSalesDTO> sumQuantityByProductIds(@Param("productIds") Collection<Long> productIds);
}
