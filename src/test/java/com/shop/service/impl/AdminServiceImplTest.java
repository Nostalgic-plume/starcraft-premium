package com.shop.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.SaTokenMockUtil;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.constant.AccountStatusEnum;
import com.shop.constant.AdminLevelEnum;
import com.shop.mapper.AdminMapper;
import com.shop.mapper.UserMapper;
import com.shop.mapper.UserStatusLogMapper;
import com.shop.model.dto.AdminLoginDTO;
import com.shop.model.entity.AdminDO;
import com.shop.model.entity.UserDO;
import com.shop.model.entity.UserStatusLogDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminServiceImpl 单元测试
 * 覆盖：管理员登录、获取当前管理员、分页查询用户、更新/批量更新用户状态
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock
    private AdminMapper adminMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserStatusLogMapper userStatusLogMapper;

    @InjectMocks
    private AdminServiceImpl adminService;

    @Captor
    private ArgumentCaptor<UserDO> userCaptor;

    @Captor
    private ArgumentCaptor<UserStatusLogDO> statusLogCaptor;

    private AdminDO createAdmin(Long id, String username, String password, int status, int level) {
        AdminDO admin = new AdminDO();
        admin.setId(id);
        admin.setUsername(username);
        admin.setPassword(password);
        admin.setNickname("管理员" + id);
        admin.setStatus(status);
        admin.setAdminLevel(level);
        return admin;
    }

    private UserDO createUser(Long id, String username, int status) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username != null ? username : "user" + id);
        user.setStatus(status);
        return user;
    }

    // ==================== 管理员登录 ====================

    @Nested
    @DisplayName("管理员登录 login()")
    class LoginTest {

        /**
         * 场景：账号密码正确，状态正常，登录成功返回 token
         */
        @Test
        void login_shouldReturnTokenMap_whenCredentialsValid() {
            // given
            AdminLoginDTO dto = new AdminLoginDTO();
            dto.setUsername("admin");
            dto.setPassword("123456");

            String bcryptPwd = DigestUtil.bcrypt("123456");
            AdminDO admin = createAdmin(1L, "admin", bcryptPwd, 0, AdminLevelEnum.SUPER.getValue());

            when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(admin);

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                // 三端会话重构后登录走 StpAdminUtil.INSTANCE.login/getTokenValue 实例方法，token 来自 mock 请求上下文

                // when
                Map<String, Object> result = adminService.login(dto);

                // then
                assertNotNull(result);
                assertEquals("mock-token", result.get("token"));
                assertEquals(1L, result.get("adminId"));
                assertEquals("管理员1", result.get("nickname"));
                verify(adminMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
            }
        }

        /**
         * 场景：管理员账号不存在，抛异常
         */
        @Test
        void login_shouldThrow_whenAdminNotFound() {
            // given
            AdminLoginDTO dto = new AdminLoginDTO();
            dto.setUsername("nonexist");
            dto.setPassword("123456");

            when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // when & then
            BusinessException ex = assertThrows(BusinessException.class, () -> adminService.login(dto));
            assertEquals("管理员账号不存在", ex.getMessage());
        }

        /**
         * 场景：密码错误，抛异常
         */
        @Test
        void login_shouldThrow_whenWrongPassword() {
            // given
            AdminLoginDTO dto = new AdminLoginDTO();
            dto.setUsername("admin");
            dto.setPassword("wrong-password");

            String bcryptPwd = DigestUtil.bcrypt("correct-password");
            AdminDO admin = createAdmin(1L, "admin", bcryptPwd, 0, AdminLevelEnum.SUPER.getValue());

            when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(admin);

            // when & then
            BusinessException ex = assertThrows(BusinessException.class, () -> adminService.login(dto));
            assertEquals("密码错误", ex.getMessage());
        }

        /**
         * 场景：管理员账号已停用（status != 0），登录被拒绝
         */
        @Test
        void login_shouldThrow_whenAccountDisabled() {
            // given
            AdminLoginDTO dto = new AdminLoginDTO();
            dto.setUsername("disabledAdmin");
            dto.setPassword("123456");

            String bcryptPwd = DigestUtil.bcrypt("123456");
            AdminDO admin = createAdmin(2L, "disabledAdmin", bcryptPwd, 1, AdminLevelEnum.NORMAL.getValue());

            when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(admin);

            // when & then
            BusinessException ex = assertThrows(BusinessException.class, () -> adminService.login(dto));
            assertEquals("管理员账号已被停用", ex.getMessage());
        }
    }

    // ==================== 获取当前管理员 ====================

    @Nested
    @DisplayName("获取当前管理员 getCurrentAdmin()")
    class GetCurrentAdminTest {

        /**
         * 场景：当前登录管理员存在，返回管理员信息
         */
        @Test
        void getCurrentAdmin_shouldReturnAdmin_whenExists() {
            // given
            AdminDO admin = createAdmin(1L, "admin", "pwd", 0, AdminLevelEnum.SUPER.getValue());

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                when(adminMapper.selectById(1L)).thenReturn(admin);

                // when
                AdminDO result = adminService.getCurrentAdmin();

                // then
                assertNotNull(result);
                assertEquals(1L, result.getId());
                assertEquals("admin", result.getUsername());
            }
        }

        /**
         * 场景：当前登录 ID 在 admin 表中不存在，抛异常
         */
        @Test
        void getCurrentAdmin_shouldThrow_whenAdminNotFound() {
            // given
            try (var login = SaTokenMockUtil.userLogin(999L)) {
                when(adminMapper.selectById(999L)).thenReturn(null);

                // when & then
                BusinessException ex = assertThrows(BusinessException.class,
                        () -> adminService.getCurrentAdmin());
                assertEquals("管理员不存在", ex.getMessage());
            }
        }
    }

    // ==================== 分页查询用户 ====================

    @Nested
    @DisplayName("分页查询用户 pageUsers()")
    class PageUsersTest {

        /**
         * 场景：无筛选条件，返回全部分页数据
         */
        @SuppressWarnings("unchecked")
        @Test
        void pageUsers_shouldReturnPageResult_whenNoFilter() {
            // given
            Page<UserDO> mockPage = mock(Page.class);
            when(mockPage.getCurrent()).thenReturn(1L);
            when(mockPage.getSize()).thenReturn(10L);
            when(mockPage.getTotal()).thenReturn(2L);
            when(mockPage.getPages()).thenReturn(1L);
            when(mockPage.getRecords()).thenReturn(Arrays.asList(
                    createUser(1L, "alice", 0),
                    createUser(2L, "bob", 1)
            ));

            when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

            // when
            PageResult<UserDO> result = adminService.pageUsers(1, 10, null, null);

            // then
            assertNotNull(result);
            assertEquals(1, result.getPage());
            assertEquals(10, result.getPageSize());
            assertEquals(2L, result.getTotal());
            assertEquals(2, result.getList().size());
        }

        /**
         * 场景：按关键字搜索用户名，返回匹配结果
         */
        @SuppressWarnings("unchecked")
        @Test
        void pageUsers_shouldFilterByKeyword_whenKeywordProvided() {
            // given
            Page<UserDO> mockPage = mock(Page.class);
            when(mockPage.getCurrent()).thenReturn(1L);
            when(mockPage.getSize()).thenReturn(10L);
            when(mockPage.getTotal()).thenReturn(1L);
            when(mockPage.getPages()).thenReturn(1L);
            when(mockPage.getRecords()).thenReturn(List.of(
                    createUser(1L, "testUser", 0)
            ));

            when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

            // when
            PageResult<UserDO> result = adminService.pageUsers(1, 10, "test", null);

            // then
            assertNotNull(result);
            assertEquals(1, result.getList().size());
            assertEquals("testUser", result.getList().get(0).getUsername());
        }

        /**
         * 场景：按状态筛选用户
         */
        @SuppressWarnings("unchecked")
        @Test
        void pageUsers_shouldFilterByStatus_whenStatusProvided() {
            // given
            Page<UserDO> mockPage = mock(Page.class);
            when(mockPage.getCurrent()).thenReturn(1L);
            when(mockPage.getSize()).thenReturn(10L);
            when(mockPage.getTotal()).thenReturn(1L);
            when(mockPage.getPages()).thenReturn(1L);
            when(mockPage.getRecords()).thenReturn(List.of(
                    createUser(3L, "disabledUser", AccountStatusEnum.DISABLED.getValue())
            ));

            when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

            // when
            PageResult<UserDO> result = adminService.pageUsers(1, 10, null, AccountStatusEnum.DISABLED.getValue());

            // then
            assertNotNull(result);
            assertEquals(AccountStatusEnum.DISABLED.getValue(), result.getList().get(0).getStatus());
        }
    }

    // ==================== 更新用户状态 ====================

    @Nested
    @DisplayName("更新用户状态 updateUserStatus()")
    class UpdateUserStatusTest {

        /**
         * 场景：操作人存在、目标用户存在、权限足够，状态更新成功并记录日志
         */
        @Test
        void updateUserStatus_shouldUpdateAndLog_whenValid() {
            // given - 超级管理员操作普通用户
            AdminDO operator = createAdmin(1L, "admin", "pwd", 0, AdminLevelEnum.SUPER.getValue());
            UserDO target = createUser(2L, "target", 0);

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                when(adminMapper.selectById(1L)).thenReturn(operator);
                when(userMapper.selectById(2L)).thenReturn(target);

                // when
                adminService.updateUserStatus(2L, AccountStatusEnum.DISABLED.getValue(), "违规发言");

                // then - 验证用户状态已更新
                verify(userMapper).updateById(userCaptor.capture());
                UserDO updatedUser = userCaptor.getValue();
                assertEquals(2L, updatedUser.getId());
                assertEquals(AccountStatusEnum.DISABLED.getValue(), updatedUser.getStatus());

                // then - 验证状态变更日志已记录
                verify(userStatusLogMapper).insert(statusLogCaptor.capture());
                UserStatusLogDO logDO = statusLogCaptor.getValue();
                assertEquals(2L, logDO.getUserId());
                assertEquals(1L, logDO.getOperatorId());
                assertEquals(0, logDO.getFromStatus());
                assertEquals(AccountStatusEnum.DISABLED.getValue(), logDO.getToStatus());
                assertEquals("违规发言", logDO.getReason());

                // then - 状态变为非 0（停用），强制下线该用户已登录会话
                // 注：三端会话重构后踢下线走 StpUserUtil.INSTANCE.kickout 实例方法，纯 Mockito 单测无法直接断言，由集成测试覆盖
            }
        }

        /**
         * 场景：操作人不存在，抛异常
         */
        @Test
        void updateUserStatus_shouldThrow_whenOperatorNotFound() {
            // given
            try (var login = SaTokenMockUtil.userLogin(999L)) {
                when(adminMapper.selectById(999L)).thenReturn(null);

                // when & then
                BusinessException ex = assertThrows(BusinessException.class,
                        () -> adminService.updateUserStatus(2L, 1, "test"));
                assertEquals("操作人不存在", ex.getMessage());

                verify(userMapper, never()).updateById(any(UserDO.class));
                verify(userStatusLogMapper, never()).insert(any(UserStatusLogDO.class));
            }
        }

        /**
         * 场景：目标用户不存在，抛异常
         */
        @Test
        void updateUserStatus_shouldThrow_whenTargetNotFound() {
            // given
            AdminDO operator = createAdmin(1L, "admin", "pwd", 0, AdminLevelEnum.SUPER.getValue());

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                when(adminMapper.selectById(1L)).thenReturn(operator);
                when(userMapper.selectById(2L)).thenReturn(null);

                // when & then
                BusinessException ex = assertThrows(BusinessException.class,
                        () -> adminService.updateUserStatus(2L, 1, "test"));
                assertEquals("目标用户不存在", ex.getMessage());

                verify(userMapper, never()).updateById(any(UserDO.class));
                verify(userStatusLogMapper, never()).insert(any(UserStatusLogDO.class));
            }
        }

        /**
         * 场景：操作人无管理员级别（adminLevel 为 NONE/0），权限不足抛异常
         * user 表已不存管理员级别，目标用户恒为普通买家，此处校验操作人自身级别
         */
        @Test
        void updateUserStatus_shouldThrow_whenNoPermission() {
            // given - 操作人 adminLevel 无效
            AdminDO operator = createAdmin(1L, "noLevelAdmin", "pwd", 0, AdminLevelEnum.NONE.getValue());
            UserDO target = createUser(2L, "user", 0);

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                when(adminMapper.selectById(1L)).thenReturn(operator);
                when(userMapper.selectById(2L)).thenReturn(target);

                // when & then
                BusinessException ex = assertThrows(BusinessException.class,
                        () -> adminService.updateUserStatus(2L, AccountStatusEnum.DISABLED.getValue(), "test"));
                assertEquals("权限不足，无法操作该用户", ex.getMessage());

                verify(userMapper, never()).updateById(any(UserDO.class));
                verify(userStatusLogMapper, never()).insert(any(UserStatusLogDO.class));
            }
        }

        /**
         * 场景：普通管理员（NORMAL）操作普通用户，应正常执行
         */
        @Test
        void updateUserStatus_shouldUpdate_whenNormalAdmin() {
            // given - 操作人 NORMAL(1)，目标为普通用户(0)，允许操作
            AdminDO operator = createAdmin(1L, "admin", "pwd", 0, AdminLevelEnum.NORMAL.getValue());
            UserDO target = createUser(2L, "normalUser", 0);

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                when(adminMapper.selectById(1L)).thenReturn(operator);
                when(userMapper.selectById(2L)).thenReturn(target);

                // when
                adminService.updateUserStatus(2L, AccountStatusEnum.DISABLED.getValue(), "违规");

                // then
                verify(userMapper).updateById(any(UserDO.class));
                verify(userStatusLogMapper).insert(any(UserStatusLogDO.class));
                // then - 状态变为非 0（停用），强制下线该用户会话（重构后走 StpUserUtil.INSTANCE，单测不直接断言）
            }
        }
    }

    // ==================== 批量更新用户状态 ====================

    @Nested
    @DisplayName("批量更新用户状态 batchUpdateUserStatus()")
    class BatchUpdateUserStatusTest {

        /**
         * 场景：批量更新多个用户状态，每个用户均正常处理
         */
        @Test
        void batchUpdateUserStatus_shouldUpdateAll_whenValid() {
            // given - 超级管理员批量操作普通用户
            List<Long> userIds = Arrays.asList(2L, 3L);
            AdminDO operator = createAdmin(1L, "superAdmin", "pwd", 0, AdminLevelEnum.SUPER.getValue());

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                when(adminMapper.selectById(1L)).thenReturn(operator);
                when(userMapper.selectById(2L)).thenReturn(createUser(2L, "user2", 0));
                when(userMapper.selectById(3L)).thenReturn(createUser(3L, "user3", 0));

                // when
                adminService.batchUpdateUserStatus(userIds, AccountStatusEnum.BANNED.getValue(), "批量封禁");

                // then - 每个用户都执行了状态更新和日志记录
                verify(userMapper, times(2)).updateById(any(UserDO.class));
                verify(userStatusLogMapper, times(2)).insert(any(UserStatusLogDO.class));
                // then - 状态变为非 0（封禁），两个用户均强制下线（重构后走 StpUserUtil.INSTANCE，单测不直接断言）
            }
        }

        /**
         * 场景：批量更新时其中一个目标用户不存在，事务中断
         */
        @Test
        void batchUpdateUserStatus_shouldThrow_whenMiddleTargetNotFound() {
            // given - 超级管理员批量操作，中间用户不存在
            List<Long> userIds = Arrays.asList(2L, 99L);
            AdminDO operator = createAdmin(1L, "superAdmin", "pwd", 0, AdminLevelEnum.SUPER.getValue());

            try (var login = SaTokenMockUtil.userLogin(1L)) {
                when(adminMapper.selectById(1L)).thenReturn(operator);
                when(userMapper.selectById(2L)).thenReturn(createUser(2L, "user2", 0));
                when(userMapper.selectById(99L)).thenReturn(null);

                // when & then - 第二个目标不存在时抛出异常
                BusinessException ex = assertThrows(BusinessException.class,
                        () -> adminService.batchUpdateUserStatus(userIds, AccountStatusEnum.DISABLED.getValue(), "批量"));
                assertEquals("目标用户不存在", ex.getMessage());

                // 第一个用户已更新并强制下线，第二个用户不存在（抛异常前不触发踢下线）
                // 注：三端会话重构后踢下线走 StpUserUtil.INSTANCE.kickout 实例方法，纯 Mockito 单测无法直接断言，由集成测试覆盖
                verify(userMapper, times(1)).updateById(any(UserDO.class));
                verify(userStatusLogMapper, times(1)).insert(any(UserStatusLogDO.class));
            }
        }
    }
}
