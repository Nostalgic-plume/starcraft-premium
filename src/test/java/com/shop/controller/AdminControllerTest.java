package com.shop.controller;

import com.shop.common.PageResult;
import com.shop.config.MockMyBatisConfig;
import com.shop.config.SaTokenConfig;
import org.springframework.context.annotation.Import;
import com.shop.model.dto.AdminLoginDTO;
import com.shop.model.entity.AdminDO;
import com.shop.model.entity.UserDO;
import com.shop.model.vo.OrderVO;
import com.shop.service.AdminService;
import com.shop.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminController 单元测试
 * 覆盖管理员登录、信息获取、用户管理（分页/状态变更）、订单管理接口
 * 注意：SaTokenConfig 被 @MockBean 整体替换，SaInterceptor 不注册，注解鉴权在 @WebMvcTest 中不生效，方法正常执行
 */
@WebMvcTest(AdminController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("AdminController - 管理员接口")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    @MockBean
    private OrderService orderService;

    @MockBean
    private SaTokenConfig saTokenConfig;

    @Test
    @DisplayName("管理员登录：返回 token 和管理员信息")
    void login_ShouldReturnToken() throws Exception {
        Map<String, Object> loginResult = Map.of(
                "token", "admin-token-xxx",
                "adminId", 1L,
                "nickname", "管理员"
        );
        given(adminService.login(any(AdminLoginDTO.class))).willReturn(loginResult);

        String requestBody = """
                {"username": "admin", "password": "123456"}
                """;

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.token", is("admin-token-xxx")));
    }

    @Test
    @DisplayName("获取当前管理员信息：返回管理员信息")
    void adminInfo_ShouldReturnAdmin() throws Exception {
        AdminDO admin = new AdminDO();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setNickname("管理员张三");

        given(adminService.getCurrentAdmin()).willReturn(admin);

        mockMvc.perform(get("/api/admin/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.username", is("admin")))
                .andExpect(jsonPath("$.data.nickname", is("管理员张三")));
    }

    @Test
    @DisplayName("用户分页列表：返回用户分页结果")
    void pageUsers_ShouldReturnPageResult() throws Exception {
        UserDO user = new UserDO();
        user.setId(10L);
        user.setUsername("testuser");
        PageResult<UserDO> pageResult = new PageResult<>();
        pageResult.setList(List.of(user));
        pageResult.setTotal(1L);

        given(adminService.pageUsers(anyInt(), anyInt(), any(), any())).willReturn(pageResult);

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("1")))
                .andExpect(jsonPath("$.data.list[0].username", is("testuser")));
    }

    @Test
    @DisplayName("修改用户状态：成功更新")
    void updateUserStatus_ShouldSucceed() throws Exception {
        doNothing().when(adminService).updateUserStatus(10L, 1, "违规操作");

        String requestBody = """
                {"status": 1, "reason": "违规操作"}
                """;

        mockMvc.perform(put("/api/admin/user/{id}/status", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("修改用户状态：不传 reason 时使用空字符串")
    void updateUserStatus_ShouldUseEmptyReason_WhenNotProvided() throws Exception {
        doNothing().when(adminService).updateUserStatus(10L, 0, "");

        String requestBody = """
                {"status": 0}
                """;

        mockMvc.perform(put("/api/admin/user/{id}/status", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("批量修改用户状态：成功批量更新")
    void batchUpdateUserStatus_ShouldSucceed() throws Exception {
        doNothing().when(adminService).batchUpdateUserStatus(anyList(), eq(1), eq("批量处理"));

        String requestBody = """
                {"ids": [10, 20], "status": 1, "reason": "批量处理"}
                """;

        mockMvc.perform(put("/api/admin/user/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("订单分页列表（管理员）：返回全平台订单")
    void orderPage_ShouldReturnAllOrders() throws Exception {
        OrderVO vo = new OrderVO();
        vo.setId(1001L);
        vo.setOrderNo("ORD20260718001");
        PageResult<OrderVO> pageResult = new PageResult<>();
        pageResult.setList(List.of(vo));
        pageResult.setTotal(1L);

        given(orderService.adminPageOrders(anyInt(), anyInt(), any(), any(), any(), any(), any()))
                .willReturn(pageResult);

        mockMvc.perform(get("/api/admin/order/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("1")));
    }

    @Test
    @DisplayName("订单详情（管理员）：返回订单信息")
    void orderDetail_ShouldReturnOrder() throws Exception {
        OrderVO vo = new OrderVO();
        vo.setId(1001L);
        vo.setOrderNo("ORD20260718001");

        given(orderService.adminGetDetail(1001L)).willReturn(vo);

        mockMvc.perform(get("/api/admin/order/detail/{orderId}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.id", is("1001")));
    }
}
