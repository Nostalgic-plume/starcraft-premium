package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.entity.PermissionDO;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限 Mapper
 *
 * @author shop
 */
public interface PermissionMapper extends BaseMapper<PermissionDO> {

    /**
     * 根据用户 ID 查询所有权限编码（跨 user_role → role_permission → permission 联表）
     */
    @Select("SELECT DISTINCT p.code FROM permission p " +
            "JOIN role_permission rp ON p.id = rp.permission_id " +
            "JOIN user_role ur ON rp.role_id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND p.status = 0 AND p.deleted = 0")
    List<String> selectCodesByUserId(Long userId);

    /**
     * 根据角色 ID 查询权限编码（为商家独立登录提供权限查询）
     */
    @Select("SELECT DISTINCT p.code FROM permission p " +
            "JOIN role_permission rp ON p.id = rp.permission_id " +
            "WHERE rp.role_id = #{roleId} AND p.status = 0 AND p.deleted = 0")
    List<String> selectCodesByRoleId(Long roleId);
}
