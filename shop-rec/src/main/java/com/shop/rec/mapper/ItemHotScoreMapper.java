package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.ItemHotScoreDO;

/**
 * 商品热度快照 Mapper（推荐库）
 * 提供热度快照表（t_item_hot_score）的基础 CRUD，供 M-04 落库与索引重建使用
 *
 * @author shop
 */
public interface ItemHotScoreMapper extends BaseMapper<ItemHotScoreDO> {
}
