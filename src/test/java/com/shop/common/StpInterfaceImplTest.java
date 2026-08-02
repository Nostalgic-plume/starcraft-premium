package com.shop.common;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.mapper.AdminMapper;
import com.shop.mapper.PermissionMapper;
import com.shop.mapper.ShopMapper;
import com.shop.mapper.UserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StpInterfaceImpl 单元测试（纯 Mockito，不起 Spring 容器）
 *
 * 验证按登录设备类型（{@code StpUtil.login(id, device)} 的第二参，即 getLoginDeviceType 返回值）路由角色与权限：
 * 1. device="admin"  → admin 角色 + 全部权限（role_permission 按 role_id=100 查询）
 * 2. device="shop"   → merchant 角色 + 商家权限（role_permission 按 role_id=101 查询）
 * 3. device="default"/null/"null" → 普通用户（user_role → role_permission 联表）
 * 4. StpUtil 抛异常  → try-catch 兜底 null → 普通用户分支
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
    @DisplayName("device=admin：角色为 [admin]，权限按 role_id=100 查询")
    void admin_ShouldReturnAdminRoleAndPermissions() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginDeviceType).thenReturn("admin");
            when(permissionMapper.selectCodesByRoleId(100L))
                    .thenReturn(List.of("user:manage", "order:all", "stats:view"));

            assertEquals(List.of("admin"), stpInterface.getRoleList(1L, "login"));
            assertEquals(List.of("user:manage", "order:all", "stats:view"),
                    stpInterface.getPermissionList(1L, "login"));

            verify(permissionMapper).selectCodesByRoleId(100L);
        }
    }

    @Test
    @DisplayName("device=shop：角色为 [merchant]，权限按 role_id=101 查询")
    void shop_ShouldReturnMerchantRoleAndPermissions() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginDeviceType).thenReturn("shop");
            when(permissionMapper.selectCodesByRoleId(101L))
                    .thenReturn(List.of("product:add", "product:edit"));

            assertEquals(List.of("merchant"), stpInterface.getRoleList(1L, "login"));
            assertEquals(List.of("product:add", "product:edit"),
                    stpInterface.getPermissionList(1L, "login"));

            verify(permissionMapper).selectCodesByRoleId(101L);
        }
    }

    @Test
    @DisplayName("device=default：普通用户，走 user_role 联表查询")
    void default_ShouldRouteToUserRoleBranch() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginDeviceType).thenReturn("default");
            when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("user"));
            when(permissionMapper.selectCodesByUserId(1L)).thenReturn(List.of("product:view"));

            assertEquals(List.of("user"), stpInterface.getRoleList(1L, "login"));
            assertEquals(List.of("product:view"), stpInterface.getPermissionList(1L, "login"));

            verify(userRoleMapper).selectRoleCodesByUserId(1L);
            verify(permissionMapper).selectCodesByUserId(1L);
        }
    }

    @Test
    @DisplayName("device=字符串 null：归一化为 null，回落普通用户分支")
    void nullString_ShouldFallbackToUserBranch() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginDeviceType).thenReturn("null");
            when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of());

            assertTrue(stpInterface.getRoleList(1L, "login").isEmpty());
            verify(userRoleMapper).selectRoleCodesByUserId(1L);
        }
    }

    @Test
    @DisplayName("StpUtil 抛异常：try-catch 兜底 null，回落普通用户分支")
    void exception_ShouldFallbackToUserBranch() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginDeviceType).thenThrow(new RuntimeException("未登录"));
            when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("user"));

            assertEquals(List.of("user"), stpInterface.getRoleList(1L, "login"));
            verify(userRoleMapper).selectRoleCodesByUserId(1L);
        }
    }
}
