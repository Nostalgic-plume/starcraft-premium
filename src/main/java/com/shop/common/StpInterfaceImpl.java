package com.shop.common;

import cn.dev33.satoken.stp.StpUtil;
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
 * 三类登录主体（按登录设备类型区分）：
 * 1. device="shop"（商家独立登录） → merchant 角色 + merchant 权限
 * 2. device="admin"（管理员独立登录） → admin 角色 + 全部权限
 * 3. device="user"/default/null（普通用户） → 从 user_role 表读取
 *
 * 注意：Sa-Token 回调本接口时拿到的 loginType 恒为 "login"（本项目统一走 StpUtil），
 * 无法用它区分三类身份，故改为读取当前登录设备类型（登录时 StpUtil.login(id, device) 的第二参）。
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

        // 按登录设备类型精准路由（loginType 在本仓库恒为 "login"，无法区分身份）
        String device = currentDeviceType();
        if ("admin".equals(device)) {
            return List.of("admin");
        } else if ("shop".equals(device)) {
            return List.of("merchant");
        }

        // 默认 device="user"/default/null = 普通用户，从 user_role 表读取
        return userRoleMapper.selectRoleCodesByUserId(id);
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long id = Long.valueOf(loginId.toString());

        // 按登录设备类型精准路由
        String device = currentDeviceType();
        if ("admin".equals(device)) {
            return permissionMapper.selectCodesByRoleId(ADMIN_ROLE_ID);
        } else if ("shop".equals(device)) {
            return permissionMapper.selectCodesByRoleId(MERCHANT_ROLE_ID);
        }

        // 默认 device="user"/default/null = 普通用户
        return permissionMapper.selectCodesByUserId(id);
    }

    /**
     * 获取当前登录会话的设备类型（登录时 {@code StpUtil.login(id, device)} 的第二参）。
     * Sa-Token 无设备信息时返回字符串 "null"，统一归一化为 null；
     * 未登录/解析异常时 try-catch 兜底返回 null，不阻断鉴权流程（普通用户分支查无权限自然 403）。
     */
    private String currentDeviceType() {
        try {
            String device = StpUtil.getLoginDeviceType();
            return "null".equals(device) ? null : device;
        } catch (Exception e) {
            return null;
        }
    }
}
