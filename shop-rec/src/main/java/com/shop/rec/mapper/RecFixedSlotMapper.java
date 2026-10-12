package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.RecFixedSlotDO;

/**
 * 固定坑位 Mapper（推荐库）
 * 提供坑位表（t_rec_fixed_slot）的基础 CRUD，供 M-09 坑位维护与 M-07 读取生效坑位使用
 *
 * @author shop
 */
public interface RecFixedSlotMapper extends BaseMapper<RecFixedSlotDO> {
}
