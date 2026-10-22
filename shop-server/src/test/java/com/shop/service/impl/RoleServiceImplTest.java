package com.shop.service.impl;

import com.shop.common.BusinessException;
import com.shop.common.MyBatisPlusTestSupport;
import com.shop.mapper.PermissionMapper;
import com.shop.mapper.RoleMapper;
import com.shop.mapper.RolePermissionMapper;
import com.shop.mapper.UserMapper;
import com.shop.mapper.UserRoleMapper;
import com.shop.model.dto.RoleDTO;
import com.shop.model.entity.PermissionDO;
import com.shop.model.entity.RoleDO;
import com.shop.model.entity.RolePermissionDO;
import com.shop.model.entity.UserDO;
import com.shop.model.entity.UserRoleDO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色权限服务测试（纯 Mockito）
 * <p>
 * 锁定三条约束：内置角色不可删、编码不可重复、用户角色分配是覆盖式的。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleServiceImpl - 内置角色保护与覆盖式分配")
class RoleServiceImplTest {

    @Mock
    private RoleMapper roleMapper;
    @Mock
    private PermissionMapper permissionMapper;
    @Mock
    private RolePermissionMapper rolePermissionMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private UserMapper userMapper;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        MyBatisPlusTestSupport.initTableInfo(RoleDO.class, PermissionDO.class,
                UserRoleDO.class, RolePermissionDO.class, UserDO.class);
    }

    private RoleServiceImpl service() {
        return new RoleServiceImpl(roleMapper, permissionMapper, rolePermissionMapper,
                userRoleMapper, userMapper);
    }

    private static RoleDO role(Long id, String code) {
        RoleDO role = new RoleDO();
        role.setId(id);
        role.setCode(code);
        role.setName(code);
        return role;
    }

    @Test
    @DisplayName("角色编码重复：拒绝新增")
    void addDuplicateCode_throws() {
        when(roleMapper.selectCount(any())).thenReturn(1L);

        RoleDTO dto = new RoleDTO();
        dto.setName("运营");
        dto.setCode("operator");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().add(dto));
        assertEquals("角色编码已存在", ex.getMessage());
        verify(roleMapper, never()).insert(any(RoleDO.class));
    }

    @Test
    @DisplayName("内置角色（admin/merchant/user）：拒绝删除")
    void deleteBuiltinRole_throws() {
        when(roleMapper.selectById(100L)).thenReturn(role(100L, "admin"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().delete(100L));
        assertEquals("内置角色不允许删除", ex.getMessage());
        verify(roleMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("角色下仍有用户：拒绝删除")
    void deleteWithUsers_throws() {
        when(roleMapper.selectById(300L)).thenReturn(role(300L, "operator"));
        when(userRoleMapper.selectCount(any())).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().delete(300L));
        assertEquals("该角色下还有 2 个用户，请先解除关联", ex.getMessage());
        verify(roleMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("用户角色分配是覆盖式：先清空再按新列表插入")
    void assignUserRoles_replacesOldLinks() {
        UserDO user = new UserDO();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(roleMapper.selectCount(any())).thenReturn(2L);

        service().assignUserRoles(9L, List.of(100L, 102L));

        verify(userRoleMapper).delete(any());
        verify(userRoleMapper, times(2)).insert(any(UserRoleDO.class));
    }

    @Test
    @DisplayName("分配不存在的角色：拒绝，且不先清空旧关联")
    void assignInvalidRole_throwsBeforeDelete() {
        UserDO user = new UserDO();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(roleMapper.selectCount(any())).thenReturn(1L); // 传了 2 个，只查到 1 个

        assertThrows(BusinessException.class,
                () -> service().assignUserRoles(9L, List.of(100L, 999L)));
        // 校验必须在删除之前：否则校验失败时旧角色已被清空
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    @DisplayName("用户不存在：拒绝分配")
    void assignToMissingUser_throws() {
        when(userMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().assignUserRoles(9L, List.of(100L)));
        assertEquals("用户不存在", ex.getMessage());
    }
}
