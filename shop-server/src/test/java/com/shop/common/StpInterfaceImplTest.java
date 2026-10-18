package com.shop.common;

import com.shop.mapper.AdminMapper;
import com.shop.mapper.PermissionMapper;
import com.shop.mapper.ShopMapper;
import com.shop.mapper.UserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StpInterfaceImpl 单元测试（纯 Mockito，不起 Spring 容器）
 *
 * 三端独立会话重构后，权限路由依据是 {@code loginType}（user / shop / admin），
 * 不再读取登录设备类型。验证：
 * 1. loginType="admin" → [admin] + role_id=100 权限
 * 2. loginType="shop"  → [merchant] + role_id=101 权限
 * 3. loginType="user"  → 查 user_role / role_permission
 * 4. 未知 loginType     → 回落普通用户分支
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StpInterfaceImpl - Sa-Token 权限加载器")
class StpInterfaceImplTest {

    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private PermissionMapper permissionMapper;
    @Mock
    private ShopMapper shopMapper;
    @Mock
    private AdminMapper adminMapper;

    @InjectMocks
    private StpInterfaceImpl stpInterface;

    @Test
    @DisplayName("loginType=admin：角色为 [admin]，权限按 role_id=100 查询")
    void admin_ShouldReturnAdminRoleAndPermissions() {
        when(permissionMapper.selectCodesByRoleId(100L))
                .thenReturn(List.of("user:manage", "order:all", "stats:view"));

        assertEquals(List.of("admin"), stpInterface.getRoleList(1L, "admin"));
        assertEquals(List.of("user:manage", "order:all", "stats:view"),
                stpInterface.getPermissionList(1L, "admin"));

        verify(permissionMapper).selectCodesByRoleId(100L);
    }

    @Test
    @DisplayName("loginType=shop：角色为 [merchant]，权限按 role_id=101 查询")
    void shop_ShouldReturnMerchantRoleAndPermissions() {
        when(permissionMapper.selectCodesByRoleId(101L))
                .thenReturn(List.of("product:add", "product:edit"));

        assertEquals(List.of("merchant"), stpInterface.getRoleList(1L, "shop"));
        assertEquals(List.of("product:add", "product:edit"),
                stpInterface.getPermissionList(1L, "shop"));

        verify(permissionMapper).selectCodesByRoleId(101L);
    }

    @Test
    @DisplayName("loginType=user：走 user_role 联表查询")
    void user_ShouldRouteToUserRoleBranch() {
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("user"));
        when(permissionMapper.selectCodesByUserId(1L)).thenReturn(List.of("product:view"));

        assertEquals(List.of("user"), stpInterface.getRoleList(1L, "user"));
        assertEquals(List.of("product:view"), stpInterface.getPermissionList(1L, "user"));

        verify(userRoleMapper).selectRoleCodesByUserId(1L);
        verify(permissionMapper).selectCodesByUserId(1L);
    }

    @Test
    @DisplayName("未知 loginType：回落普通用户分支")
    void unknownLoginType_ShouldFallbackToUserBranch() {
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("user"));

        assertEquals(List.of("user"), stpInterface.getRoleList(1L, "unknown"));
        verify(userRoleMapper).selectRoleCodesByUserId(1L);
    }
}
