package com.shop.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * CancelPendingService 单元测试
 * 重点验证三态判定：反悔优先、提问不执行、只有明确确认才执行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelPendingService - pending 状态与三态判定")
class CancelPendingServiceTest {

    private static final String KEY = "chat:pending:cancel:1001";

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CancelPendingService service;

    @BeforeEach
    void setUp() {
        service = new CancelPendingService(redisTemplate, new ObjectMapper());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("明确确认：确认取消 / 确定 / 是的 → CONFIRM")
    void judge_confirm() {
        assertEquals(CancelPendingService.Reply.CONFIRM, service.judge("确认取消"));
        assertEquals(CancelPendingService.Reply.CONFIRM, service.judge("确定"));
        assertEquals(CancelPendingService.Reply.CONFIRM, service.judge("是的"));
    }

    @Test
    @DisplayName("反悔：不要取消 / 不想取消 / 算了 → REVERSE（优先于确认）")
    void judge_reverse() {
        assertEquals(CancelPendingService.Reply.REVERSE, service.judge("不要取消"));
        assertEquals(CancelPendingService.Reply.REVERSE, service.judge("我不想取消订单了"));
        assertEquals(CancelPendingService.Reply.REVERSE, service.judge("算了"));
        assertEquals(CancelPendingService.Reply.REVERSE, service.judge("确认取消？算了不要取消"));
    }

    @Test
    @DisplayName("提问不执行：取消要手续费吗 / 确认取消吗 → OTHER")
    void judge_question_shouldNotConfirm() {
        assertEquals(CancelPendingService.Reply.OTHER, service.judge("取消要手续费吗？"));
        assertEquals(CancelPendingService.Reply.OTHER, service.judge("确认取消吗"));
        assertEquals(CancelPendingService.Reply.OTHER, service.judge("确认取消会退款吗"));
    }

    @Test
    @DisplayName("其他/长句/空消息 → OTHER")
    void judge_other() {
        assertEquals(CancelPendingService.Reply.OTHER, service.judge("好的"));
        assertEquals(CancelPendingService.Reply.OTHER, service.judge("帮我把昨天那笔订单取消了吧，麻烦了"));
        assertEquals(CancelPendingService.Reply.OTHER, service.judge(" "));
        assertEquals(CancelPendingService.Reply.OTHER, service.judge(null));
    }

    @Test
    @DisplayName("写入 pending：JSON 含 orderId/userId，TTL 5 分钟")
    void write_shouldStoreJsonWithTtl() {
        service.write("1001", 1L, 2072224811785306000L);

        verify(valueOperations).set(eq(KEY), contains("2072224811785306000"), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("读取 pending：解析 JSON")
    void get_shouldParseJson() {
        when(valueOperations.get(KEY))
                .thenReturn("{\"orderId\":2072224811785306000,\"userId\":1}");

        CancelPendingService.PendingOrder pending = service.get("1001");

        assertNotNull(pending);
        assertEquals(2072224811785306000L, pending.orderId());
        assertEquals(1L, pending.userId());
    }

    @Test
    @DisplayName("清除 pending")
    void clear() {
        service.clear("1001");
        verify(redisTemplate).delete(KEY);
    }
}
