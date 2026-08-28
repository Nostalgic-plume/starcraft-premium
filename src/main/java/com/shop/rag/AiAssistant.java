package com.shop.rag;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * AI 客服 Agent 接口（LangChain4j AiServices 代理接口）
 * <p>
 * 返回 {@link TokenStream} 表示流式输出（SSE）。
 * 记忆通过 AiServices 绑定的 {@code chatMemory} 提供（每请求单会话绑定，接口无需 @MemoryId 参数），
 * 用户消息由 @UserMessage 注入；agent loop（工具调用决策/执行/回填）由 AiServices 框架处理。
 * <p>
 * 2026-08-28 从手动 Agent Loop 迁回 AiServices：裁决测试（DeepSeekAiServicesSmokeTest）实测
 * DeepSeek 兼容 AiServices 流式 agent loop（qwen-plus 不兼容），故换模型 + 用回框架。
 *
 * @author shop
 */
public interface AiAssistant {

    /**
     * 流式对话（agent loop 内自动处理工具调用）
     *
     * @param userMessage 用户本轮消息
     * @return 流式响应 TokenStream（onPartialResponse / onCompleteResponse / onError 回调）
     */
    TokenStream chat(@UserMessage String userMessage);
}
