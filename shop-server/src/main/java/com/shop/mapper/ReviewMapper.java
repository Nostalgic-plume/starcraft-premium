package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.entity.ReviewDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 商品评价 Mapper
 *
 * @author shop
 */
public interface ReviewMapper extends BaseMapper<ReviewDO> {

    /**
     * 物理删除评价
     * <p>
     * 不能用 {@code deleteById}（实体有 @TableLogic，只会置 deleted=1）：
     * 逻辑删除后 {@code uk_order_item} 唯一索引仍被占用，该订单项将永远无法再次评价。
     * 评价属可重建的弱内容，管理端删除即视为彻底移除并释放订单项。
     *
     * @param id 评价 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM review WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
