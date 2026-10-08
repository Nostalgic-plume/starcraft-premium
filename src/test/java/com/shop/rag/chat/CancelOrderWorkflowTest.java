package com.shop.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.BusinessException;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.rag.memory.ChatHistoryService;
import com.shop.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * CancelOrderWorkflow 单元测试
 * 验证「写操作受控 + 跨轮确认」安全语义：
 * 1. 取消意图 + 订单号 → 只写 pending + 返回确认话术，绝不直接取消
 * 2. pending 存在 + 用户确认 → 才确定性执行 cancelOrderByUserId
 * 3. pending 存在 + 反悔/转话题 → 不执行取消
 * 4. 非取消意图（无 pending）→ workflow 不消费，落回默认
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelOrderWorkflow - 取消订单受控流程")
class CancelOrderWorkflowTest {

    @Mock
    private OrderService orderService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ChatHistoryService chatHistoryService;

    private CancelOrderWorkflow workflow;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        workflow = new CancelOrderWorkflow(orderService, redisTemplate);
        objectMapper = new ObjectMapper();
        // lenient：各用例只用到部分 stub
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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

    private OrderVO pendingOrder(Long id) {
        OrderVO o = new OrderVO();
        o.setId(id);
        o.setOrderNo(String.valueOf(id));
        o.setStatus(OrderConstants.STATUS_PENDING_PAY);
        o.setStatusText("待支付");
        o.setTotalAmount(new BigDecimal("99.00"));
        o.setActualAmount(new BigDecimal("99.00"));
        return o;
    }

    @Nested
    @DisplayName("轮1：请求取消")
    class FirstRoundTest {

        @Test
        @DisplayName("含订单号的取消请求：只写 pending 并返回确认话术，不直接取消")
        void cancelRequest_WithOrderNo_ShouldWritePendingAndAskConfirm() {
            OrderVO order = pendingOrder(2072224811785306000L);
            when(orderService.getOrderDetailByUserId(eq(2072224811785306000L), eq(1L))).thenReturn(order);

            boolean handled = workflow.tryHandle(
                    ctx("帮我取消订单 2072224811785306000"), Intent.ORDER_CANCEL);

            assertTrue(handled, "取消意图应由本 workflow 消费");
            // 写 pending，未调取消写操作
            verify(valueOperations).set(eq("chat:pending:cancel:1001"), eq("2072224811785306000"), anyLong(), any());
            verify(orderService, never()).cancelOrderByUserId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("无订单号：列出待支付订单引导，不写 pending 不取消")
        void cancelRequest_WithoutOrderNo_ShouldGuide() {
            when(orderService.myOrdersByUserId(1L, OrderConstants.STATUS_PENDING_PAY, 5))
                    .thenReturn(List.of(pendingOrder(2072224811785306000L)));

            boolean handled = workflow.tryHandle(ctx("我想退掉昨天那单"), Intent.ORDER_CANCEL);

            assertTrue(handled);
            verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
            verify(orderService, never()).cancelOrderByUserId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("订单不属于本人：返回固定文案，不写 pending")
        void cancelRequest_NotOwned_ShouldNotWritePending() {
            when(orderService.getOrderDetailByUserId(anyLong(), eq(1L)))
                    .thenThrow(new BusinessException("订单不存在"));

            boolean handled = workflow.tryHandle(
                    ctx("取消订单 2072224811785306000"), Intent.ORDER_CANCEL);

            assertTrue(handled);
            verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("轮2：跨轮确认")
    class SecondRoundTest {

        @Test
        @DisplayName("pending 存在 + 用户确认：确定性执行取消并清 pending")
        void confirm_WithPending_ShouldCancel() {
            when(valueOperations.get("chat:pending:cancel:1001")).thenReturn("2072224811785306000");

            boolean handled = workflow.tryHandle(ctx("确认取消"), Intent.PRODUCT);

            assertTrue(handled);
            verify(orderService).cancelOrderByUserId(eq(2072224811785306000L), eq(1L));
            verify(redisTemplate).delete("chat:pending:cancel:1001");
        }

        @Test
        @DisplayName("pending 存在但用户反悔：不执行取消，清 pending 落回默认")
        void regret_WithPending_ShouldNotCancel() {
            when(valueOperations.get("chat:pending:cancel:1001")).thenReturn("2072224811785306000");

            boolean handled = workflow.tryHandle(ctx("算了不取消了"), Intent.ORDER_CANCEL);

            assertFalse(handled, "反悔消息不应由 workflow 消费执行");
            verify(orderService, never()).cancelOrderByUserId(anyLong(), anyLong());
            verify(redisTemplate).delete("chat:pending:cancel:1001");
        }

        @Test
        @DisplayName("无 pending 且非取消意图：workflow 不消费")
        void noPending_NotCancelIntent_ShouldReturnFalse() {
            boolean handled = workflow.tryHandle(ctx("有什么手机推荐吗"), Intent.PRODUCT);

            assertFalse(handled);
            verify(orderService, never()).cancelOrderByUserId(anyLong(), anyLong());
        }
    }
}
