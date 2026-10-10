package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.RecFilterRuleDO;

/**
 * 过滤规则 Mapper（推荐库）
 * 提供规则表（t_rec_filter_rule）的基础 CRUD，供 M-09 规则维护与 M-06 过滤清单加载使用
 *
 * @author shop
 */
public interface RecFilterRuleMapper extends BaseMapper<RecFilterRuleDO> {
}
