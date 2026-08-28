package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.entity.UserRoleDO;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户-角色关联 Mapper
 *
 * @author shop
 */
public interface UserRoleMapper extends BaseMapper<UserRoleDO> {

    /**
     * 根据用户 ID 查询所有角色编码
     */
    @Select("SELECT r.code FROM role r " +
            "JOIN user_role ur ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.status = 0 AND r.deleted = 0")
    List<String> selectRoleCodesByUserId(Long userId);
}
