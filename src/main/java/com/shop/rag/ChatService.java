package com.shop.rag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能客服服务
 * 基于 RAG（检索增强生成）+ 流式 SSE 输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final OllamaStreamingChatModel streamingChatModel;
    private final RagSearchService ragSearchService;

    /** 会话记忆池：conversationId → ChatMemory（保留最近 10 轮） */
    private final Map<String, MessageWindowChatMemory> chatMemoryMap = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static final int MAX_TOKENS = 10;
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个专业的电商智能客服助手，请基于以下商品信息回答用户的问题。

            如果信息足以回答，请给出专业、简洁的推荐或解答。
            如果信息不足以回答，请诚实告知用户你不知道，不要编造信息。

            ===== 相关商品信息 =====
            %s
            =========================

            请用中文回答，语气友好专业。
            """;

    /**
     * 流式问答（SSE）
     *
     * @param conversationId 会话ID
     * @param userMessage    用户消息
     * @param emitter        SSE 发射器
     */
    public void ask(String conversationId, String userMessage, SseEmitter emitter) {
        executor.execute(() -> {
            try {
                // 1. RAG 检索 TOP-3
                List<RagHitVO> hits = ragSearchService.search(userMessage, 3, null, null, null);

                // 2. 构建商品上下文
                String productContext = buildProductContext(hits);

                // 3. 获取或创建会话记忆
                MessageWindowChatMemory chatMemory = chatMemoryMap.computeIfAbsent(
                        conversationId,
                        k -> MessageWindowChatMemory.builder()
                                .maxMessages(MAX_TOKENS)
                                .build()
                );

                // 4. 构建消息列表
                String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, productContext);
                chatMemory.add(new SystemMessage(systemPrompt));
                chatMemory.add(new UserMessage(userMessage));

                // 5. 流式调用 Ollama
                StringBuilder fullResponse = new StringBuilder();
                streamingChatModel.generate(chatMemory.messages(), new dev.langchain4j.model.StreamingResponseHandler<AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        fullResponse.append(token);
                        sendEvent(emitter, "token", token);
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        // 保存 AI 回复到记忆
                        chatMemory.add(response.content());
                        sendEvent(emitter, "end", "");
                        try {
                            emitter.complete();
                        } catch (Exception e) {
                            log.warn("SSE complete 异常: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("Ollama 流式调用失败", error);
                        sendEvent(emitter, "error", "AI 回复生成失败，请稍后重试");
                        sendEvent(emitter, "end", "");
                        try {
                            emitter.complete();
                        } catch (Exception e) {
                            log.warn("SSE complete 异常: {}", e.getMessage());
                        }
                    }
                });

            } catch (Exception e) {
                log.error("ChatService 处理异常", e);
                sendEvent(emitter, "error", "服务异常，请稍后重试");
                sendEvent(emitter, "end", "");
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * 清理会话（对话结束或超时后调用）
     */
    public void clearConversation(String conversationId) {
        chatMemoryMap.remove(conversationId);
    }

    private String buildProductContext(List<RagHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return "（未找到相关商品信息，请根据你的知识回答）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            RagHitVO hit = hits.get(i);
            sb.append("--- 商品 ").append(i + 1).append(" ---\n");
            sb.append("名称：").append(hit.getName()).append("\n");
            sb.append("价格：").append(hit.getMinPrice());
            if (hit.getMaxPrice() != null && !hit.getMaxPrice().equals(hit.getMinPrice())) {
                sb.append(" ~ ").append(hit.getMaxPrice());
            }
            sb.append("\n");
            sb.append("摘要：").append(hit.getExcerpt()).append("\n\n");
        }
        return sb.toString();
    }

    private void sendEvent(SseEmitter emitter, String type, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data("{\"type\":\"" + type + "\",\"content\":\"" + escapeJson(content) + "}"));
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 发送失败（连接可能已关闭）: {}", e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
