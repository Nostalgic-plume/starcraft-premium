package com.shop.common;

import com.shop.mapper.AdminMapper;
import com.shop.mapper.PermissionMapper;
import com.shop.mapper.ShopMapper;
import com.shop.mapper.UserRoleMapper;
import com.shop.model.entity.AdminDO;
import com.shop.model.entity.ShopDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限加载器
 * 实现 StpInterface，Sa-Token 在鉴权时自动调用此接口
 *
 * 三类登录主体（按查询顺序）：
 * 1. shop_id（商家独立登录） → merchant 角色 + merchant 权限
 * 2. admin_id（管理员独立登录） → admin 角色 + 全部权限
 * 3. user_id（普通用户） → 从 user_role 表读取
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements cn.dev33.satoken.stp.StpInterface {

    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;
    private final ShopMapper shopMapper;
    private final AdminMapper adminMapper;

    /** merchant 角色在 role 表中的固定 ID */
    private static final Long MERCHANT_ROLE_ID = 101L;
    /** admin 角色在 role 表中的固定 ID */
    private static final Long ADMIN_ROLE_ID = 100L;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long id = Long.valueOf(loginId.toString());

        // 按 loginType 精准路由，避免逐个探测所有表
        if ("admin".equals(loginType)) {
            return List.of("admin");
        } else if ("shop".equals(loginType)) {
            return List.of("merchant");
        }

        // 默认 login 类型 = 普通用户，从 user_role 表读取
        return userRoleMapper.selectRoleCodesByUserId(id);
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long id = Long.valueOf(loginId.toString());

        // 按 loginType 精准路由
        if ("admin".equals(loginType)) {
            return permissionMapper.selectCodesByRoleId(ADMIN_ROLE_ID);
        } else if ("shop".equals(loginType)) {
            return permissionMapper.selectCodesByRoleId(MERCHANT_ROLE_ID);
        }

        // 默认 login 类型 = 普通用户
        return permissionMapper.selectCodesByUserId(id);
    }
}
