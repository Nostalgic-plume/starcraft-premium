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
 * 三类登录主体（按 loginType 区分，三端独立会话）：
 * 1. loginType="shop"（商家，StpShopUtil） → merchant 角色 + merchant 权限
 * 2. loginType="admin"（管理员，StpAdminUtil） → admin 角色 + 全部权限
 * 3. loginType="user"（普通用户，StpUserUtil） → 从 user_role 表读取
 *
 * Sa-Token 回调本接口时 loginType 参数即当前 StpLogic 的 loginType（user/shop/admin），
 * 据此精准路由，不再依赖登录设备类型。
 *
 * @author shop
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

    /**
     * 返回登录主体的角色编码列表（Sa-Token 鉴权时自动调用）
     * 按当前登录设备类型路由：admin → [admin]；shop → [merchant]；普通用户 → 查 user_role 表
     *
     * @param loginId   登录主体 ID（admin/shop 表 id 或 user 表 id）
     * @param loginType 登录类型（本项目恒为 "login"，不使用该参数）
     * @return 角色编码列表，无角色时返回空列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long id = Long.valueOf(loginId.toString());

        // 按 loginType 精准路由（三端独立会话）
        if ("shop".equals(loginType)) {
            return List.of("merchant");
        } else if ("admin".equals(loginType)) {
            return List.of("admin");
        }

        // 默认 loginType="user" = 普通用户，从 user_role 表读取
        return userRoleMapper.selectRoleCodesByUserId(id);
    }

    /**
     * 返回登录主体的权限编码列表（Sa-Token 鉴权时自动调用）
     * 按当前登录设备类型路由：admin → 查固定 admin 角色权限；shop → 查固定 merchant 角色权限；
     * 普通用户 → 按用户 ID 联表查询其所有角色权限
     *
     * @param loginId   登录主体 ID（admin/shop 表 id 或 user 表 id）
     * @param loginType 登录类型（本项目恒为 "login"，不使用该参数）
     * @return 权限编码列表，无权限时返回空列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long id = Long.valueOf(loginId.toString());

        // 按 loginType 精准路由（三端独立会话）
        if ("shop".equals(loginType)) {
            return permissionMapper.selectCodesByRoleId(MERCHANT_ROLE_ID);
        } else if ("admin".equals(loginType)) {
            return permissionMapper.selectCodesByRoleId(ADMIN_ROLE_ID);
        }

        // 默认 loginType="user" = 普通用户
        return permissionMapper.selectCodesByUserId(id);
    }
}
