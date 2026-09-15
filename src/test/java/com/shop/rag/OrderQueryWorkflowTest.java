package com.shop.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderQueryWorkflow 单元测试
 * 验证订单查询受控 workflow：结构化查询（订单号/状态/全量）由确定性代码执行并消费；
 * 模糊长句返回 false 交回只读 agent 兜底；非 ORDER_QUERY 意图不处理
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderQueryWorkflow - 订单查询受控流程")
class OrderQueryWorkflowTest {

    @Mock
    private OrderService orderService;

    @Mock
    private ChatHistoryService chatHistoryService;

    private OrderQueryWorkflow workflow;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        workflow = new OrderQueryWorkflow(orderService);
        objectMapper = new ObjectMapper();
        lenient().when(chatHistoryService.listMessages(any())).thenReturn(List.of());
    }

    private WorkflowContext ctx(String msg) {
        return WorkflowContext.builder()
                .conversationId("1001")
                .userId(1L)
                .userMessage(msg)
                .emitter(mock(SseEmitter.class))
                .objectMapper(objectMapper)
                .chatHistoryService(chatHistoryService)
                .build();
    }

    private OrderVO order(Long id, int status) {
        OrderVO o = new OrderVO();
        o.setId(id);
        o.setOrderNo(String.valueOf(id));
        o.setStatus(status);
        o.setStatusText("待支付");
        o.setTotalAmount(new BigDecimal("99.00"));
        o.setActualAmount(new BigDecimal("99.00"));
        o.setCreateTime(LocalDateTime.now());
        o.setItems(List.of());
        return o;
    }

    @Test
    @DisplayName("含订单号：确定性查详情并消费")
    void withOrderNo_ShouldQueryDetailDeterministically() {
        OrderVO o = order(2072224811785306000L, OrderConstants.STATUS_PENDING_PAY);
        when(orderService.getOrderDetailByUserId(eq(2072224811785306000L), eq(1L))).thenReturn(o);

        boolean handled = workflow.tryHandle(ctx("帮我查一下订单 2072224811785306000"), Intent.ORDER_QUERY);

        assertTrue(handled);
        verify(orderService).getOrderDetailByUserId(eq(2072224811785306000L), eq(1L));
    }

    @Test
    @DisplayName("含状态词：确定性按状态查列表并消费")
    void withStatusWord_ShouldQueryListByStatus() {
        when(orderService.myOrdersByUserId(1L, OrderConstants.STATUS_PAID, 10))
                .thenReturn(List.of(order(1L, OrderConstants.STATUS_PAID)));

        boolean handled = workflow.tryHandle(ctx("我的待发货订单有哪些"), Intent.ORDER_QUERY);

        assertTrue(handled);
        verify(orderService).myOrdersByUserId(1L, OrderConstants.STATUS_PAID, 10);
    }

    @Test
    @DisplayName("查全部：确定性查全部并消费")
    void queryAll_ShouldQueryAll() {
        when(orderService.myOrdersByUserId(1L, null, 10)).thenReturn(List.of(order(1L, OrderConstants.STATUS_PENDING_PAY)));

        boolean handled = workflow.tryHandle(ctx("都买了什么，我的订单"), Intent.ORDER_QUERY);

        assertTrue(handled);
        verify(orderService).myOrdersByUserId(1L, null, 10);
    }

    @Test
    @DisplayName("模糊长句无法规则解析：返回 false 交回只读 agent 兜底")
    void vagueMessage_ShouldReturnFalse() {
        boolean handled = workflow.tryHandle(ctx("我上周买的那件外套现在到哪了"), Intent.ORDER_QUERY);

        assertFalse(handled);
        verify(orderService, never()).getOrderDetailByUserId(any(), any());
        verify(orderService, never()).myOrdersByUserId(any(), any(), anyInt());
    }

    @Test
    @DisplayName("非 ORDER_QUERY 意图：不处理")
    void notQueryIntent_ShouldReturnFalse() {
        boolean handled = workflow.tryHandle(ctx("帮我取消订单 123"), Intent.ORDER_CANCEL);
        assertFalse(handled);
    }
}
