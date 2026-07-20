package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.mapper.RoleMapper;
import com.shop.mapper.UserMapper;
import com.shop.mapper.UserRoleMapper;
import com.shop.model.dto.UserLoginDTO;
import com.shop.model.dto.UserRegisterDTO;
import com.shop.model.entity.RoleDO;
import com.shop.model.entity.UserDO;
import com.shop.model.entity.UserRoleDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserService 单元测试
 * 覆盖注册、登录、查询等核心业务逻辑
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService - 用户服务")
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @InjectMocks
    private UserServiceImpl userService;

    private UserRegisterDTO registerDTO;
    private UserLoginDTO loginDTO;
    private UserDO normalUser;

    @BeforeEach
    void setUp() {
        registerDTO = new UserRegisterDTO();
        registerDTO.setUsername("testuser");
        registerDTO.setPassword("123456");
        registerDTO.setPhone("13800138000");
        registerDTO.setNickname("测试用户");

        loginDTO = new UserLoginDTO();
        loginDTO.setUsername("testuser");
        loginDTO.setPassword("123456");

        normalUser = new UserDO();
        normalUser.setId(1L);
        normalUser.setUsername("testuser");
        normalUser.setPassword("$2a$10$hashhashhashashashashashashashashashah");
        normalUser.setStatus(0);
        normalUser.setNickname("测试用户");
    }

    @Nested
    @DisplayName("注册 - register()")
    class RegisterTest {

        @Test
        @DisplayName("成功注册：用户名可用，分配角色")
        void register_ShouldSucceed_WhenUsernameNotTaken() {
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcrypt(anyString())).thenReturn("$2a$10$hashedpassword");

                RoleDO userRole = new RoleDO();
                userRole.setId(100L);
                userRole.setCode("user");
                when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(userRole);

                userService.register(registerDTO);

                verify(userMapper).insert(any(UserDO.class));
                verify(userRoleMapper).insert(any(UserRoleDO.class));
            }
        }

        @Test
        @DisplayName("注册失败：用户名已存在")
        void register_ShouldThrow_WhenUsernameExists() {
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.register(registerDTO));
            assertEquals("用户名已存在", ex.getMessage());
            verify(userMapper, never()).insert(any());
        }

        @Test
        @DisplayName("注册成功：角色不存在时跳过角色分配")
        void register_ShouldSucceed_WhenRoleNotFound() {
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcrypt(anyString())).thenReturn("$2a$10$hashed");

                when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

                userService.register(registerDTO);

                verify(userMapper).insert(any(UserDO.class));
                verify(userRoleMapper, never()).insert(any());
            }
        }

        @Test
        @DisplayName("注册成功：昵称为空时自动使用用户名")
        void register_ShouldUseUsername_WhenNicknameBlank() {
            registerDTO.setNickname(null);
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcrypt(anyString())).thenReturn("$2a$10$hashed");

                RoleDO userRole = new RoleDO();
                userRole.setId(100L);
                userRole.setCode("user");
                when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(userRole);

                userService.register(registerDTO);

                verify(userMapper).insert(argThat(user ->
                        user.getNickname() != null && user.getNickname().equals("testuser")));
            }
        }
    }

    @Nested
    @DisplayName("登录 - login()")
    class LoginTest {

        @Test
        @DisplayName("成功登录：凭据正确且账号正常")
        void login_ShouldReturnToken_WhenCredentialsValid() {
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(normalUser);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class);
                 MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {

                digestUtil.when(() -> DigestUtil.bcryptCheck(anyString(), anyString())).thenReturn(true);
                stpUtil.when(StpUtil::getTokenValue).thenReturn("sa-token-xxxxx");

                String token = userService.login(loginDTO);

                assertEquals("sa-token-xxxxx", token);
                stpUtil.verify(() -> StpUtil.login(1L));
            }
        }

        @Test
        @DisplayName("登录失败：用户名不存在")
        void login_ShouldThrow_WhenUserNotFound() {
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.login(loginDTO));
            assertEquals("用户名或密码错误", ex.getMessage());
        }

        @Test
        @DisplayName("登录失败：密码错误")
        void login_ShouldThrow_WhenPasswordWrong() {
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(normalUser);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcryptCheck(anyString(), anyString())).thenReturn(false);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> userService.login(loginDTO));
                assertEquals("用户名或密码错误", ex.getMessage());
            }
        }

        @Test
        @DisplayName("登录失败：账号已停用")
        void login_ShouldThrow_WhenAccountDisabled() {
            normalUser.setStatus(1);
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(normalUser);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcryptCheck(anyString(), anyString())).thenReturn(true);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> userService.login(loginDTO));
                assertEquals("账号已被停用，请联系管理员", ex.getMessage());
            }
        }

        @Test
        @DisplayName("登录失败：账号注销中")
        void login_ShouldThrow_WhenAccountCancelling() {
            normalUser.setStatus(2);
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(normalUser);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcryptCheck(anyString(), anyString())).thenReturn(true);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> userService.login(loginDTO));
                assertEquals("账号正在注销中，无法登录", ex.getMessage());
            }
        }

        @Test
        @DisplayName("登录失败：账号被封禁")
        void login_ShouldThrow_WhenAccountBanned() {
            normalUser.setStatus(3);
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(normalUser);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcryptCheck(anyString(), anyString())).thenReturn(true);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> userService.login(loginDTO));
                assertEquals("账号已被封禁，请联系管理员", ex.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("查询 - getById / getCurrentUser / listAll")
    class QueryTest {

        @Test
        @DisplayName("根据ID查询：用户存在")
        void getById_ShouldReturnUser_WhenExists() {
            when(userMapper.selectById(1L)).thenReturn(normalUser);

            UserDO result = userService.getById(1L);

            assertNotNull(result);
            assertEquals("testuser", result.getUsername());
        }

        @Test
        @DisplayName("根据ID查询：用户不存在")
        void getById_ShouldThrow_WhenNotExists() {
            when(userMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.getById(99L));
            assertEquals("用户不存在", ex.getMessage());
        }

        @Test
        @DisplayName("获取当前用户：已登录")
        void getCurrentUser_ShouldReturnUser_WhenLoggedIn() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(userMapper.selectById(1L)).thenReturn(normalUser);

                UserDO result = userService.getCurrentUser();

                assertNotNull(result);
                assertEquals("testuser", result.getUsername());
            }
        }

        @Test
        @DisplayName("获取当前用户：用户不存在")
        void getCurrentUser_ShouldThrow_WhenUserNotFound() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
                when(userMapper.selectById(99L)).thenReturn(null);

                assertThrows(BusinessException.class, () -> userService.getCurrentUser());
            }
        }

        @Test
        @DisplayName("查询所有用户")
        void listAll_ShouldReturnList() {
            List<UserDO> users = List.of(normalUser);
            when(userMapper.selectList(null)).thenReturn(users);

            List<UserDO> result = userService.listAll();

            assertEquals(1, result.size());
            assertEquals("testuser", result.get(0).getUsername());
        }
    }
}
