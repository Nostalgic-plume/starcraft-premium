package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.UserProfileDO;

/**
 * 用户画像 Mapper（推荐库）
 * 提供画像表（t_user_profile）的基础 CRUD，供 M-03 画像生成与版本回滚使用
 *
 * @author shop
 */
public interface UserProfileMapper extends BaseMapper<UserProfileDO> {
}
