package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.entity.UserDO;

/**
 * 用户 Mapper
 * 提供用户表（user）的基础 CRUD，继承 MyBatis-Plus BaseMapper
 */
public interface UserMapper extends BaseMapper<UserDO> {
}
