package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.entity.SkuDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * SKU Mapper
 * 除了基础 CRUD，提供行锁扣库存和回滚库存操作（用于并发控制）
 */
public interface SkuMapper extends BaseMapper<SkuDO> {

    /**
     * 行锁扣库存
     * 利用 MySQL 行级锁（WHERE id=? AND stock>=?）防止并发超卖
     *
     * @param skuId    SKU ID
     * @param quantity 扣减数量
     * @return 影响行数（0 表示库存不足，扣减失败）
     */
    @Update("UPDATE sku SET stock = stock - #{quantity} WHERE id = #{skuId} AND stock >= #{quantity}")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 回滚库存
     * 取消订单时恢复已被扣减的库存
     *
     * @param skuId    SKU ID
     * @param quantity 恢复数量·
     */
    @Update("UPDATE sku SET stock = stock + #{quantity} WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
}
