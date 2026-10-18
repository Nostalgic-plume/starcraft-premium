package com.shop.controller;

import cn.dev33.satoken.stp.StpInterface;
import com.shop.common.SaTokenMockUtil;
import com.shop.config.MockMyBatisConfig;
import com.shop.config.SaTokenConfig;
import org.springframework.context.annotation.Import;
import com.shop.common.PageResult;
import com.shop.common.Result;
import com.shop.model.dto.UserLoginDTO;
import com.shop.model.dto.UserRegisterDTO;
import com.shop.model.entity.UserDO;
import com.shop.service.AdminService;
import com.shop.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserController 单元测试
 * 覆盖注册、登录、信息获取、退出、角色查询、用户列表接口
 * 注意：SaTokenConfig 被 @MockBean 整体替换，SaInterceptor 不注册，注解鉴权在 @WebMvcTest 中不生效，方法正常执行
 */
@WebMvcTest(UserController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("UserController - 用户接口")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private AdminService adminService;

    @MockBean
    private SaTokenConfig saTokenConfig;

    @Test
    @DisplayName("用户注册：成功注册")
    void register_ShouldSucceed() throws Exception {
        doNothing().when(userService).register(any(UserRegisterDTO.class));

        String requestBody = """
                {
                    "username": "newuser",
                    "password": "123456",
                    "nickname": "新用户"
                }
                """;

        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("注册成功")));
    }

    @Test
    @DisplayName("用户登录：返回 token")
    void login_ShouldReturnToken() throws Exception {
        given(userService.login(any(UserLoginDTO.class))).willReturn("sa-token-user-xxx");

        String requestBody = """
                {"username": "testuser", "password": "123456"}
                """;

        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.token", is("sa-token-user-xxx")));
    }

    @Test
    @DisplayName("获取当前用户信息：返回用户信息")
    void info_ShouldReturnUserInfo() throws Exception {
        UserDO user = new UserDO();
        user.setId(1L);
        user.setUsername("testuser");
        user.setNickname("测试用户");

        given(userService.getCurrentUser()).willReturn(user);

        mockMvc.perform(get("/api/user/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.username", is("testuser")))
                .andExpect(jsonPath("$.data.nickname", is("测试用户")));
    }

    @Test
    @DisplayName("退出登录：返回成功")
    void logout_ShouldReturnSuccess() throws Exception {
        try (var scope = SaTokenMockUtil.userLogin(1L)) {
            mockMvc.perform(post("/api/user/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(200)));
        }
    }

    @Test
    @DisplayName("获取角色列表：返回角色编码")
    void getRoles_ShouldReturnRoleList() throws Exception {
        StpInterface stpInterface = mock(StpInterface.class);
        // loginId 由 Sa-Token 从 Dao 以字符串形式取出，这里用 any() 匹配，避免 Long/String 形态差异
        when(stpInterface.getRoleList(any(), eq("user"))).thenReturn(List.of("user", "merchant"));

        try (var scope = SaTokenMockUtil.userLogin(1L, stpInterface)) {
            mockMvc.perform(get("/api/user/role"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(200)))
                    .andExpect(jsonPath("$.data", hasItems("user", "merchant")));
        }
    }

    @Test
    @DisplayName("用户列表：返回分页结果")
    void list_ShouldReturnPageResult() throws Exception {
        UserDO user = new UserDO();
        user.setId(1L);
        user.setUsername("testuser");
        user.setNickname("测试用户");

        PageResult<UserDO> pageResult = new PageResult<>();
        pageResult.setList(List.of(user));
        pageResult.setTotal(1L);

        given(adminService.pageUsers(anyInt(), anyInt(), any(), any())).willReturn(pageResult);

        mockMvc.perform(get("/api/user/list")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("1")))
                .andExpect(jsonPath("$.data.list[0].username", is("testuser")));
    }

    @Test
    @DisplayName("用户列表：按关键词筛选")
    void list_ShouldFilterByKeyword() throws Exception {
        PageResult<UserDO> emptyResult = new PageResult<>();
        emptyResult.setList(List.of());
        emptyResult.setTotal(0L);
        given(adminService.pageUsers(anyInt(), anyInt(), eq("test"), isNull()))
                .willReturn(emptyResult);

        mockMvc.perform(get("/api/user/list")
                        .param("keyword", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }
}
