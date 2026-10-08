package com.shop.rag.chat;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ChatIntentResolver 单元测试
 * 验证「规则优先 + LLM 兜底」：明确取消/查询强词直接规则命中（不触发 LLM）；
 * 模糊订单词走 LLM 分类一次；LLM 失败回默认 PRODUCT
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatIntentResolver - 意图识别")
class ChatIntentResolverTest {

    @Mock
    private OpenAiStreamingChatModel streamingChatModel;

    private ChatIntentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChatIntentResolver(streamingChatModel);
    }

    @Test
    @DisplayName("规则命中：取消订单强词 → ORDER_CANCEL（不触发 LLM）")
    void rule_CancelWords_ShouldReturnCancel() {
        assertEquals(Intent.ORDER_CANCEL, resolver.resolve("帮我取消订单 123"));
        assertEquals(Intent.ORDER_CANCEL, resolver.resolve("这笔订单我想退掉"));
        verify(streamingChatModel, never()).chat(anyList(), any());
    }

    @Test
    @DisplayName("规则命中：查订单强词 → ORDER_QUERY")
    void rule_QueryWords_ShouldReturnQuery() {
        assertEquals(Intent.ORDER_QUERY, resolver.resolve("我的订单到哪了"));
        assertEquals(Intent.ORDER_QUERY, resolver.resolve("买了什么 / 帮我查一下物流"));
        verify(streamingChatModel, never()).chat(anyList(), any());
    }

    @Test
    @DisplayName("无关消息 → PRODUCT（不触发 LLM）")
    void rule_Product_ShouldReturnProduct() {
        assertEquals(Intent.PRODUCT, resolver.resolve("推荐一款性价比高的手机"));
        verify(streamingChatModel, never()).chat(anyList(), any());
    }

    @Test
    @DisplayName("LLM 兜底：模糊订单词分类为 cancel → ORDER_CANCEL")
    void llmFallback_Cancel_ShouldReturnCancel() {
        mockLlmReply("cancel");
        assertEquals(Intent.ORDER_CANCEL, resolver.resolve("上次那个单帮我处理一下"));
        verify(streamingChatModel).chat(anyList(), any());
    }

    @Test
    @DisplayName("LLM 兜底失败 → 默认 PRODUCT")
    void llmFallback_Error_ShouldReturnProduct() {
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onError(new RuntimeException("model down"));
            return null;
        }).when(streamingChatModel).chat(anyList(), any());

        assertEquals(Intent.PRODUCT, resolver.resolve("那个单有点问题"));
    }

    /** 让 mock LLM 流式吐出一个词后完成 */
    private void mockLlmReply(String word) {
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse(word);
            handler.onCompleteResponse(mock(ChatResponse.class));
            return null;
        }).when(streamingChatModel).chat(anyList(), any());
    }

    @Test
    @DisplayName("规则命中：闲聊强信号 → CHITCHAT（不触发 LLM）")
    void rule_ChitchatWords_ShouldReturnChitchat() {
        assertEquals(Intent.CHITCHAT, resolver.resolve("能不能陪我聊天"));
        assertEquals(Intent.CHITCHAT, resolver.resolve("你好"));
        assertEquals(Intent.CHITCHAT, resolver.resolve("谢谢你呀"));
        assertEquals(Intent.CHITCHAT, resolver.resolve("你是谁"));
        assertEquals(Intent.CHITCHAT, resolver.resolve("今天心情不好，想找人说说话"));
        verify(streamingChatModel, never()).chat(anyList(), any());
    }

    @Test
    @DisplayName("闲聊词 + 商品信号词 → 仍判 PRODUCT（守门，不误伤导购）")
    void rule_ChitchatWordWithProductSignal_ShouldReturnProduct() {
        assertEquals(Intent.PRODUCT, resolver.resolve("你好，手机多少钱"));
        assertEquals(Intent.PRODUCT, resolver.resolve("陪我看看冰箱推荐"));
        assertEquals(Intent.PRODUCT, resolver.resolve("谢谢，帮我推荐个耳机"));
        verify(streamingChatModel, never()).chat(anyList(), any());
    }
}
