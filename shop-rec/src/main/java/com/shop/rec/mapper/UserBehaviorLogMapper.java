package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.UserBehaviorLogDO;

/**
 * 用户行为明细 Mapper（推荐库）
 * 提供行为明细表（t_user_behavior_log）的基础 CRUD，供 M-01 异步落库与 M-03 画像取数使用
 *
 * @author shop
 */
public interface UserBehaviorLogMapper extends BaseMapper<UserBehaviorLogDO> {
}
