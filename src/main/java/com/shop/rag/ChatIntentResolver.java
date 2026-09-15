package com.shop.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 客服意图识别（规则优先 + LLM 兜底）
 * <p>
 * 规则层用强信号关键词做低延迟、确定性的意图判定（取消/查订单）；
 * 规则无法可靠判定但命中订单域词时，才触发一次 LLM 分类兜底，避免每轮都引入额外 LLM 延迟；
 * LLM 失败或无关消息默认 {@link Intent#PRODUCT}。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatIntentResolver {

    @Qualifier("openAiStreamingChatModel")
    private final OpenAiStreamingChatModel streamingChatModel;

    private static final String CLASSIFY_SYSTEM = "你是电商客服的意图分类器，只输出一个英文单词，不要任何解释。";

    private static final String CLASSIFY_PROMPT_TEMPLATE = """
            把用户消息归类为以下三种意图之一，只输出对应英文单词（不要标点/解释）：
            - cancel：取消订单（写操作）——用户明确要退单/取消某笔订单
            - query：查询订单（只读）——用户问自己订单的状态/物流/买了什么
            - product：商品咨询/导购/闲聊——其余所有情况

            用户消息：%s

            意图：""";

    /**
     * 识别用户消息意图
     *
     * @param message 用户消息
     * @return 意图（永不返回 null，LLM 兜底失败时默认 PRODUCT）
     */
    public Intent resolve(String message) {
        if (message == null || message.isBlank()) {
            return Intent.PRODUCT;
        }

        // ① 规则优先：取消订单（写操作）强信号
        if (hasAny(message, "取消", "退单", "退掉", "退了", "不想要了", "拒收", "别发货", "别发")) {
            return Intent.ORDER_CANCEL;
        }

        // ② 规则优先：查订单（只读）强信号
        if (hasAny(message, "我的订单", "订单列表", "有哪些订单", "什么订单", "有什么订单", "都买了什么",
                "待收货", "待发货", "待付款", "快递", "物流")) {
            return Intent.ORDER_QUERY;
        }
        if (message.contains("订单") && hasAny(message, "到哪", "发货", "状态", "进度", "查", "看一下", "催",
                "哪些", "列表", "有啥", "几个")) {
            return Intent.ORDER_QUERY;
        }

        // ③ 命中订单域但规则不明确 → LLM 兜底一次（cancel/query/product）
        if (message.contains("订单") || message.contains("单") || message.contains("快递")
                || message.contains("退") || message.contains("取消")) {
            Intent llmIntent = classifyByLlm(message);
            if (llmIntent != null) {
                return llmIntent;
            }
        }

        return Intent.PRODUCT;
    }

    /** 命中订单取消词但消息很短（"退了""取消"）也按取消意图走受控流程，由 workflow 引导订单定位 */
    private boolean hasAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM 兜底分类：一次非流式调用，输出 cancel/query/product 之一；
     * 失败或输出无法识别返回 null（调用方回默认 PRODUCT）
     */
    private Intent classifyByLlm(String message) {
        String prompt = String.format(CLASSIFY_PROMPT_TEMPLATE, message);
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            List<ChatMessage> messages = List.of(
                    new SystemMessage(CLASSIFY_SYSTEM),
                    new UserMessage(prompt));

            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                private final StringBuilder sb = new StringBuilder();

                @Override
                public void onPartialResponse(String token) {
                    sb.append(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    future.complete(sb.toString().trim());
                }

                @Override
                public void onError(Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            String raw = future.get(8, TimeUnit.SECONDS).toLowerCase(Locale.ROOT);
            if (raw.contains("cancel")) {
                return Intent.ORDER_CANCEL;
            }
            if (raw.contains("query")) {
                return Intent.ORDER_QUERY;
            }
            return null;
        } catch (Exception e) {
            log.debug("LLM 意图分类失败，回默认意图: {}", e.getMessage());
            return null;
        }
    }
}
