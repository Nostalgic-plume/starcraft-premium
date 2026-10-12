package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.RecConfigDO;

/**
 * 推荐策略配置 Mapper（推荐库）
 * 提供配置表（t_rec_config）的基础 CRUD，供 M-09 配置读写与副本同步使用
 *
 * @author shop
 */
public interface RecConfigMapper extends BaseMapper<RecConfigDO> {
}
