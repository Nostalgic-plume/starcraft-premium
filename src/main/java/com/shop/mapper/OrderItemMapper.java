package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.entity.OrderItemDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单商品项 Mapper
 * 提供订单商品项表（order_item）的基础 CRUD
 */
public interface OrderItemMapper extends BaseMapper<OrderItemDO> {

    /**
     * 统计商品累计销量（已完成订单的有效销量）
     */
    @Select("SELECT COALESCE(SUM(oi.quantity), 0) FROM order_item oi " +
            "JOIN `orders` o ON oi.order_id = o.id " +
            "WHERE oi.product_id = #{productId} AND o.status IN (1,2,3,4)")
    Integer sumQuantityByProductId(@Param("productId") Long productId);
}
