package com.shop.rag.chat;

import com.shop.common.BusinessException;
import com.shop.model.entity.ChatConversationDO;
import com.shop.rag.memory.ChatHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * ChatService#clearConversation 的会话归属校验单元测试
 * <p>
 * 回归 IDOR 隐患：{@code conversationId} 由前端生成并随请求传入，清理动作必须先比对会话归属，
 * 否则任何持有（或猜到）该 ID 的请求方都能清空他人的对话历史。
 * <p>
 * 调用方 {@code ChatController#clearConversation} 从登录态取 userId 传入，
 * 本测试锁定服务层这道防线本身：本人放行、他人拒绝、会话不存在拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService - 清除会话的归属校验")
class ChatServiceClearConversationTest {

    /** 贴近前端实际生成的 19 位会话 ID（时间戳 + 6 位随机数） */
    private static final long CONV_ID = 1728712345678123456L;
    private static final long OWNER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Mock
    private ChatHistoryService chatHistoryService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        // 本用例只涉及 chatHistoryService，其余依赖传 null（构造器仅赋值，不做校验）
        chatService = new ChatService(
                null,   // streamingChatModel
                null,   // streamingChatModelBackup
                null,   // circuitBreaker
                null,   // toolCallLogger
                null,   // ragSearchService
                null,   // ragHitEnricher
                chatHistoryService,
                null,   // queryRewriter
                null,   // objectMapper
                null,   // categoryMapper
                null,   // productMapper
                null,   // mySqlChatMemoryStore
                null,   // orderService
                null,   // skuPriceResolver
                null,   // chatIntentResolver
                null,   // cancelOrderWorkflow
                null);  // orderQueryWorkflow
    }

    @Test
    @DisplayName("清除自己的会话：放行")
    void clear_ownConversation_shouldPass() {
        when(chatHistoryService.getConversation(CONV_ID)).thenReturn(conversation(CONV_ID, OWNER_ID));

        assertDoesNotThrow(() -> chatService.clearConversation(String.valueOf(CONV_ID), OWNER_ID));
    }

    @Test
    @DisplayName("清除他人会话：拒绝，且不产生任何清理动作")
    void clear_othersConversation_shouldReject() {
        when(chatHistoryService.getConversation(CONV_ID)).thenReturn(conversation(CONV_ID, OWNER_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.clearConversation(String.valueOf(CONV_ID), OTHER_USER_ID));
        assertEquals("无权访问该会话", ex.getMessage());
    }

    @Test
    @DisplayName("会话不存在：拒绝（不因查不到就放行）")
    void clear_nonexistentConversation_shouldReject() {
        when(chatHistoryService.getConversation(CONV_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> chatService.clearConversation(String.valueOf(CONV_ID), OWNER_ID));
    }

    @Test
    @DisplayName("conversationId 非数字（前端兜底值 \"default\"）：按无权访问拒绝，不抛 NumberFormatException")
    void clear_nonNumericConversationId_shouldRejectGracefully() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.clearConversation("default", OWNER_ID));
        assertEquals("无权访问该会话", ex.getMessage());
    }

    private static ChatConversationDO conversation(Long id, Long userId) {
        ChatConversationDO conv = new ChatConversationDO();
        conv.setId(id);
        conv.setUserId(userId);
        return conv;
    }
}
