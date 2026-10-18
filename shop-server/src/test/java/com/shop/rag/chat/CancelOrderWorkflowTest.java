package com.shop.rag.chat;

import com.shop.common.BusinessException;
import com.shop.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CancelOrderWorkflow 单元测试
 * 验证「执行阶段」的安全语义：只执行确定性取消，失败返回文案而不抛异常。
 * 提议阶段（查单 / 写 pending）已移到 {@link RequestCancelTool}，由 RequestCancelToolTest 覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelOrderWorkflow - 确定性执行取消")
class CancelOrderWorkflowTest {

    @Mock
    private OrderService orderService;

    private CancelOrderWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new CancelOrderWorkflow(orderService);
    }

    @Test
    @DisplayName("执行成功：调用 cancelOrderByUserId 并返回成功文案")
    void execute_success_shouldCancel() {
        String result = workflow.execute(1L, "1001", 2072224811785306000L);

        verify(orderService).cancelOrderByUserId(eq(2072224811785306000L), eq(1L));
        assertTrue(result.contains("已成功取消"));
    }

    @Test
    @DisplayName("业务异常：返回失败文案，不向外抛异常")
    void execute_businessError_shouldReturnMessage() {
        doThrow(new BusinessException("当前订单状态不允许取消"))
                .when(orderService).cancelOrderByUserId(anyLong(), anyLong());

        String result = assertDoesNotThrow(
                () -> workflow.execute(1L, "1001", 2072224811785306000L));

        assertTrue(result.contains("取消失败"));
        assertTrue(result.contains("当前订单状态不允许取消"));
    }

    @Test
    @DisplayName("执行器不再消费普通消息：tryHandle 恒返回 false")
    void tryHandle_shouldNotConsume() {
        assertFalse(workflow.tryHandle(null, Intent.ORDER_CANCEL));
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("extractOrderId：提取 ≥10 位连续数字")
    void extractOrderId() {
        assertEquals(2072224811785306000L,
                CancelOrderWorkflow.extractOrderId("取消订单 2072224811785306000"));
        assertNull(CancelOrderWorkflow.extractOrderId("取消昨天的订单"));
        assertNull(CancelOrderWorkflow.extractOrderId(null));
    }
}
