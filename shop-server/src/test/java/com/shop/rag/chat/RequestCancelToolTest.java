package com.shop.rag.chat;

import com.shop.common.BusinessException;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RequestCancelTool 单元测试
 * 核心断言：工具只写 pending，绝不调用取消写操作。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RequestCancelTool - 取消请求（半写）")
class RequestCancelToolTest {

    private static final Long ORDER_ID = 2072224811785306000L;

    @Mock
    private OrderService orderService;

    @Mock
    private CancelPendingService cancelPendingService;

    private RequestCancelTool tool;

    @BeforeEach
    void setUp() {
        tool = new RequestCancelTool(1L, "1001", orderService, cancelPendingService);
    }

    private OrderVO order(Integer status, String statusText) {
        OrderVO order = new OrderVO();
        order.setId(ORDER_ID);
        order.setOrderNo(String.valueOf(ORDER_ID));
        order.setStatus(status);
        order.setStatusText(statusText);
        order.setTotalAmount(new BigDecimal("99.00"));
        order.setActualAmount(new BigDecimal("99.00"));
        order.setCreateTime(LocalDateTime.of(2026, 10, 17, 10, 0));
        return order;
    }

    @Test
    @DisplayName("待支付订单：只写 pending + 返回确认话术，不执行取消")
    void request_pendingOrder_shouldWritePendingOnly() {
        when(orderService.getOrderDetailByUserId(ORDER_ID, 1L))
                .thenReturn(order(OrderConstants.STATUS_PENDING_PAY, "待支付"));

        String result = tool.requestCancelOrder(String.valueOf(ORDER_ID));

        assertTrue(result.contains("确认取消"));
        verify(cancelPendingService).write("1001", 1L, ORDER_ID);
        verify(orderService, never()).cancelOrderByUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("非本人订单：统一文案，不写 pending")
    void request_notOwned_shouldNotWritePending() {
        when(orderService.getOrderDetailByUserId(ORDER_ID, 1L))
                .thenThrow(new BusinessException("订单不存在"));

        String result = tool.requestCancelOrder(String.valueOf(ORDER_ID));

        assertEquals("该订单不存在或不属于您", result);
        verify(cancelPendingService, never()).write(anyString(), anyLong(), anyLong());
        verify(orderService, never()).cancelOrderByUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("非待支付状态：提示不可取消，不写 pending")
    void request_paidOrder_shouldNotWritePending() {
        when(orderService.getOrderDetailByUserId(ORDER_ID, 1L))
                .thenReturn(order(OrderConstants.STATUS_PAID, "待发货"));

        String result = tool.requestCancelOrder(String.valueOf(ORDER_ID));

        assertTrue(result.contains("仅待支付订单可取消"));
        verify(cancelPendingService, never()).write(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("非法订单号：直接返回，不查库")
    void request_invalidOrderId_shouldNotQuery() {
        assertEquals("订单编号无效，请确认后重试", tool.requestCancelOrder("abc"));
        assertEquals("订单编号无效，请确认后重试", tool.requestCancelOrder(null));
        verifyNoInteractions(orderService);
        verifyNoInteractions(cancelPendingService);
    }
}
