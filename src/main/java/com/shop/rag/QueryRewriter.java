package com.shop.rag;

import dev.langchain4j.data.message.AiMessage;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Query 改写服务
 * <p>
 * 利用 LLM 将用户短 query 结合对话历史改写为独立、完整的商品搜索关键词，
 * 解决口语化输入和指代消解问题。
 * <p>
 * 例如：
 * - 用户： 「有什么手机推荐？」 → 下一轮「红色的」→ 改写为「红色手机」
 * - 用户： 「便宜的手机」 → 改写为「低价手机 性价比」
 * - 用户： 「有充电宝吗」 → 改写为「充电宝 移动电源」（补充同义表述，提升关键词路召回）
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriter {

    @Qualifier("openAiStreamingChatModel")
    private final OpenAiStreamingChatModel streamingChatModel;

    private static final String REWRITE_SYSTEM = "你是一个电商搜索关键词改写助手。";

    private static final String PROMPT_TEMPLATE = """
            将用户的对话消息改写为独立、完整的商品搜索关键词。

            对话历史：
            %s

            当前用户消息：%s

            请将当前用户消息改写为一个完整的商品搜索关键词。
            要求：
            - 补全指代（如「这个」→「红色连衣裙」，「那款」→「iPhone 16 Pro」）
            - 保留关键属性（颜色、尺寸、价格范围、品牌等）
            - 若用户用词与商品常见表述不同（如「充电宝」→「移动电源」、「刮胡刀」→「剃须刀」、「球鞋」→「运动鞋」），
              可在关键词后补充其同义表述，用空格分隔；只补充同义表述，不得改变或扩大商品品类
            - 输出仅包含搜索关键词本身，不要任何解释、引号或标点符号
            - 如果当前消息本身已经是完整的搜索查询，直接原样输出
            - 输出字符不超过 50 字

            改写结果：""";

    /**
     * 对用户消息进行改写，补全指代和上下文
     *
     * @param userMessage    当前用户消息
     * @param recentMessages 最近对话历史（用于消解指代）
     * @return 改写后的搜索关键词，改写失败时返回原始消息
     */
    public String rewrite(String userMessage, List<ChatMessage> recentMessages) {
        if (userMessage == null || userMessage.isBlank()) {
            return userMessage;
        }

        String history = buildHistoryPrompt(recentMessages);
        String prompt = String.format(PROMPT_TEMPLATE, history, userMessage);

        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            List<ChatMessage> messages = List.of(
                    new SystemMessage(REWRITE_SYSTEM),
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

            String rewritten = future.get(10, TimeUnit.SECONDS);
            log.debug("Query 改写: '{}' → '{}'", userMessage, rewritten);
            return rewritten;

        } catch (Exception e) {
            log.warn("Query 改写失败，使用原始消息: {}", e.getMessage());
            return userMessage;
        }
    }

    /**
     * 将最近对话历史格式化为 prompt 文本
     */
    private String buildHistoryPrompt(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "（无历史对话）";
        }

        // 取最近 6 条（约 3 轮对话），排除 system 消息
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < 6; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage) continue;

            if (msg instanceof UserMessage userMsg) {
                sb.insert(0, "用户：" + userMsg.singleText() + "\n");
                count++;
            } else if (msg instanceof AiMessage aiMsg) {
                String text = aiMsg.text();
                if (text != null && !text.isBlank()) {
                    sb.insert(0, "助手：" + text + "\n");
                    count++;
                }
            }
        }
        return sb.isEmpty() ? "（无历史对话）" : sb.toString();
    }
}
