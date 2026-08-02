package com.shop.controller;

import com.shop.common.PageResult;
import com.shop.config.MockMyBatisConfig;
import com.shop.config.SaTokenConfig;
import org.springframework.context.annotation.Import;
import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OrderController 单元测试
 * 覆盖下单防重令牌、创建订单、取消订单、确认收货、订单详情、订单列表接口
 * 注意：SaTokenConfig 被 @MockBean 整体替换，SaInterceptor 不注册，注解鉴权在 @WebMvcTest 中不生效，方法正常执行
 */
@WebMvcTest(OrderController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("OrderController - 订单接口")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private SaTokenConfig saTokenConfig;

    @Test
    @DisplayName("获取下单防重令牌：返回 token")
    void getToken_ShouldReturnToken() throws Exception {
        given(orderService.getOrderToken()).willReturn("order-token-xxx");

        mockMvc.perform(get("/api/order/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.token", is("order-token-xxx")));
    }

    @Test
    @DisplayName("创建订单：返回处理标识 processingId")
    void createOrder_ShouldReturnProcessingId() throws Exception {
        given(orderService.createOrder(any(OrderCreateDTO.class))).willReturn("1001");

        String requestBody = """
                {
                    "token": "test-order-token",
                    "items": [{"skuId": 100, "quantity": 2}],
                    "addressId": 10,
                    "remark": "测试订单"
                }
                """;

        mockMvc.perform(post("/api/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("下单请求已提交")))
                .andExpect(jsonPath("$.data", is("1001")));
    }

    @Test
    @DisplayName("取消订单：成功取消")
    void cancelOrder_ShouldSucceed() throws Exception {
        doNothing().when(orderService).cancelOrder(1001L);

        mockMvc.perform(post("/api/order/cancel/{orderId}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("确认收货：成功确认")
    void confirmReceive_ShouldSucceed() throws Exception {
        doNothing().when(orderService).confirmReceive(1001L);

        mockMvc.perform(post("/api/order/receive/{orderId}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("订单详情：返回订单 VO")
    void detail_ShouldReturnOrderVO() throws Exception {
        OrderVO vo = new OrderVO();
        vo.setId(1001L);
        vo.setOrderNo("ORD20260718001");
        vo.setStatus(0);

        given(orderService.getDetail(1001L)).willReturn(vo);

        mockMvc.perform(get("/api/order/detail/{orderId}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.id", is("1001")))
                .andExpect(jsonPath("$.data.orderNo", is("ORD20260718001")));
    }

    @Test
    @DisplayName("我的订单列表：返回分页结果")
    void myOrders_ShouldReturnPageResult() throws Exception {
        OrderVO vo = new OrderVO();
        vo.setId(1001L);
        vo.setOrderNo("ORD20260718001");

        PageResult<OrderVO> pageResult = new PageResult<>();
        pageResult.setList(java.util.List.of(vo));
        pageResult.setTotal(1L);

        given(orderService.myOrders(anyInt(), anyInt(), any())).willReturn(pageResult);

        mockMvc.perform(get("/api/order/my")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("1")))
                .andExpect(jsonPath("$.data.list[0].orderNo", is("ORD20260718001")));
    }

    @Test
    @DisplayName("我的订单列表：按状态筛选")
    void myOrders_ShouldFilterByStatus() throws Exception {
        PageResult<OrderVO> emptyResult = new PageResult<>();
        emptyResult.setList(java.util.List.of());
        emptyResult.setTotal(0L);

        given(orderService.myOrders(anyInt(), anyInt(), eq(1))).willReturn(emptyResult);

        mockMvc.perform(get("/api/order/my")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("0")));
    }
}
