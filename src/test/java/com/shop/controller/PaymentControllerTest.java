package com.shop.controller;

import com.shop.config.MockMyBatisConfig;
import com.shop.service.OrderService;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PaymentController 单元测试
 * 覆盖支付回调和模拟支付接口
 */
@WebMvcTest(PaymentController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("PaymentController - 支付接口")
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    @DisplayName("支付回调：返回字符串 success")
    void callback_ShouldReturnSuccess() throws Exception {
        doNothing().when(orderService).handlePaymentCallback("ORDER001", 1);

        mockMvc.perform(post("/api/pay/callback")
                        .param("orderNo", "ORDER001")
                        .param("payType", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        verify(orderService).handlePaymentCallback("ORDER001", 1);
    }

    @Test
    @DisplayName("模拟支付：返回 Result 包装的成功响应")
    void mockPay_ShouldReturnResultSuccess() throws Exception {
        doNothing().when(orderService).handlePaymentCallback("ORDER002", 2);

        mockMvc.perform(post("/api/pay/mock-pay")
                        .param("orderNo", "ORDER002")
                        .param("payType", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("支付成功"));

        verify(orderService).handlePaymentCallback("ORDER002", 2);
    }

    @Test
    @DisplayName("模拟支付：使用默认 payType=1")
    void mockPay_ShouldUseDefaultPayType() throws Exception {
        doNothing().when(orderService).handlePaymentCallback("ORDER003", 1);

        mockMvc.perform(post("/api/pay/mock-pay")
                        .param("orderNo", "ORDER003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("支付回调：缺少必填参数时全局异常处理器兜底返回 200 + 错误码")
    void callback_ShouldReturnError_WhenMissingParam() throws Exception {
        mockMvc.perform(post("/api/pay/callback")
                        .param("orderNo", "ORDER001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").exists());
    }
}
