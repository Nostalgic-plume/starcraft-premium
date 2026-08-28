package com.shop.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ChatMessageDO;
import com.shop.model.entity.ProductDO;
import com.shop.service.OrderService;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能客服服务
 * 基于 RAG（检索增强生成）+ 流式 SSE 输出
 * 对话记录持久化到 MySQL
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    @Qualifier("openAiStreamingChatModel")
    private final OpenAiStreamingChatModel streamingChatModel;
    @Qualifier("openAiStreamingChatModelBackup")
    private final OpenAiStreamingChatModel streamingChatModelBackup;
    private final CircuitBreaker circuitBreaker;
    private final ToolCallLogger toolCallLogger;
    private final RagSearchService ragSearchService;
    private final ChatHistoryService chatHistoryService;
    private final QueryRewriter queryRewriter;
    private final ObjectMapper objectMapper;
    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final MySqlChatMemoryStore mySqlChatMemoryStore;
    private final OrderService orderService;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 对话窗口 token 预算（TokenWindowChatMemory 按此裁剪，防 token 爆炸） */
    private static final int TOKEN_BUDGET = 3000;
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是「星辰优选」商城的智能客服「小星」，性格热情耐心，像朋友一样帮用户挑商品。

            ## 核心风格
            - 语气自然亲切，像线下导购员一样说话，不要生硬堆砌参数
            - 回答简洁不啰嗦，不用「您好」「感谢」「请问有什么可以帮助您」这类开场白
            - 首句直接切入正题，用自然的口语

            ## 当有商品信息时
            根据下方商品列表向用户推荐，结合自己的理解说人话，例如：
            - 「这款【商品名】性价比很高，才XX元，很多用户反馈不错💪」
            - 「目前有这几款符合您的要求，我比较推荐第三款……」
            - 用表情符号（😊👍💪🔥）适当点缀，别刷屏

            ## 当没有商品信息时
            下面商品列表为空，说明目前没有匹配到具体商品，你需要：
            1. 判断用户是不是在问一个商品类别（如「手机」「电脑」「衣服」）
            2. 如果是类别名，直接追问具体要求：「手机是吧，您想要什么价位的？主要用途是？」—— 像导购一样自然引导
            3. 如果不是类别，就告知没找到，并反问具体需求：「没搜到您说的这个，您具体想找什么类型的东西？我帮您看看～」
            4. 不要道歉，不要说「抱歉」「对不起」「还没有上架」这类话，直接追问引导

            ===== 相关商品信息 =====
            %s
            =========================

            ## 工具调用规则
            - 用户查询/操作订单（查订单、取消订单等）时，必须先调用对应工具拿到真实结果后再回答
            - 不要提前承诺"已取消/已完成"；工具未调用前不要声称操作成功
            - 不要凭记忆编造订单信息，订单号不存在时如实告知

            记住：你是小星，不是机器人。说话要像真人导购。
            """;

    /**
     * 流式问答（SSE）
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param userMessage    用户消息
     * @param emitter        SSE 发射器
     */
    public void ask(String conversationId, Long userId, String userMessage, SseEmitter emitter) {
        executor.execute(() -> {
            try {
                // 1. 确保会话存在（MySQL持久化）
                chatHistoryService.getOrCreateConversation(Long.valueOf(conversationId), userId);

                // 1.5 清理 agent loop 缓存：本轮从 DB 恢复干净历史，避免工具往返消息跨轮污染
                mySqlChatMemoryStore.deleteMessages(conversationId);

                // 2. 每轮重建会话记忆（从 MySqlChatMemoryStore 读最近消息；不缓存内存对象，
                //    避免工具调用中间消息残留下轮窗口，且保证重启恢复行为不变）
                TokenWindowChatMemory chatMemory = TokenWindowChatMemory.builder()
                        .id(conversationId)
                        .maxTokens(TOKEN_BUDGET, new OpenAiTokenCountEstimator("gpt-4o"))
                        .chatMemoryStore(mySqlChatMemoryStore)
                        .build();

                // 3. Query 改写：利用 LLM 补全指代、优化搜索关键词
                String searchQuery = queryRewriter.rewrite(userMessage, chatMemory.messages());
                log.debug("Query 改写: '{}' → '{}'", userMessage, searchQuery);

                // 4. 混合 RAG 检索 TOP-3（使用改写后的 Query）
                List<RagHitVO> hits = ragSearchService.search(searchQuery, 3, null, null, null);

                // 5. RAG 无结果时，尝试按分类名回退查询数据库（使用原始消息）
                if (hits == null || hits.isEmpty()) {
                    List<RagHitVO> categoryHits = fallbackSearchByCategory(userMessage);
                    if (categoryHits != null && !categoryHits.isEmpty()) {
                        hits = categoryHits;
                        log.info("分类回退命中: {} 条, query={}", hits.size(), userMessage);
                    }
                }

                // 6. 构建商品上下文
                String productContext = buildProductContext(hits);

                // 7. 保存用户消息到 MySQL
                ChatMessageDO userMsgDO = new ChatMessageDO();
                userMsgDO.setConversationId(Long.valueOf(conversationId));
                userMsgDO.setRole("user");
                userMsgDO.setContent(userMessage);
                userMsgDO.setRagContext(buildRagContextJson(hits));
                chatHistoryService.saveMessage(userMsgDO);

                // 8. 构建 system prompt（每轮单条最新）并加入本轮 memory；同步写入 store 缓存，
                //    确保 AiServices 第一轮从 store.getMessages 能读到含 system 的完整窗口
                String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, productContext);
                chatMemory.add(new SystemMessage(systemPrompt));
                mySqlChatMemoryStore.updateMessages(conversationId, chatMemory.messages());

                // 9. 生成层降级链（Phase 5 T5）：
                //    DeepSeek(带工具) → 熔断 → qwen 纯 RAG 问答(不带工具) → 模板拼检索结果 → 提示不可用
                if (circuitBreaker.isOpen()) {
                    log.warn("DeepSeek 熔断中 → 降级到 qwen 纯 RAG 问答（不调用工具）");
                    qwenRagFallback(systemPrompt, productContext, userMessage, conversationId, emitter);
                } else {
                    deepSeekAgentLoop(userId, systemPrompt, productContext, chatMemory,
                            userMessage, conversationId, emitter);
                }

            } catch (Exception e) {
                log.error("ChatService 处理异常", e);
                sendEvent(emitter, "error", "服务异常，请稍后重试");
                completeSse(emitter, "");
            }
        });
    }

    /**
     * 主链路：DeepSeek + AiServices 工具链（agent loop）
     * 熔断挂载：onError → onFailure()（并当场降级 qwen）；onCompleteResponse → onSuccess()
     */
    private void deepSeekAgentLoop(Long userId, String systemPrompt, String productContext,
                                   TokenWindowChatMemory chatMemory, String userMessage,
                                   String conversationId, SseEmitter emitter) {
        AiAssistant assistant = AiServices.builder(AiAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemory(chatMemory)
                .tools(new OrderTool(userId, orderService))
                .maxToolCallingRoundTrips(5)
                .compensateOnToolErrors(true)
                // 工具容错（Phase 5 T4）：
                // ① 参数解析/校验错误 → 错误信息回填给 LLM 修正重试（不直接降级）
                // ② LLM 幻觉的工具名 → 返回"工具不存在"回填（不让它瞎编）
                .toolArgumentsErrorHandler((error, context) ->
                        ToolErrorHandlerResult.text("工具参数无效：" + error.getMessage() + "，请检查参数后重新调用"))
                .hallucinatedToolNameStrategy(request ->
                        new ToolExecutionResultMessage(request.id(), request.name(),
                                "该工具不存在，请使用已提供的工具"))
                .build();

        assistant.chat(userMessage)
                .onPartialResponse(token -> {
                    if (token == null || token.isBlank()) {
                        return; // 工具决策阶段的空 token 不推送
                    }
                    sendEvent(emitter, "token", token);
                })
                .onPartialToolCall(toolCall ->
                        log.debug("工具调用意图: {} args={}", toolCall.name(), toolCall.partialArguments()))
                .onToolExecuted(toolExecuted -> {
                    boolean failed = toolExecuted.hasFailed();
                    long cost = toolExecuted.duration() != null ? toolExecuted.duration().toMillis() : -1;
                    // 工具日志（异步，可观测性）：只记录元数据，不记录 PII 参数/结果
                    toolCallLogger.log(userId, toolExecuted.request().name(), cost, !failed, null);
                    if (failed) {
                        log.warn("工具执行失败: {}", toolExecuted.request().name());
                    } else {
                        log.info("工具执行成功: {} 耗时{}ms", toolExecuted.request().name(), cost);
                    }
                })
                .onCompleteResponse(response -> {
                    circuitBreaker.onSuccess();
                    String reply = response != null && response.aiMessage() != null
                            ? response.aiMessage().text() : "";
                    saveAssistantMessage(conversationId, reply);
                    completeSse(emitter, reply);
                })
                .onError(error -> {
                    circuitBreaker.onFailure();
                    log.error("DeepSeek 调用失败，当场降级到 qwen 纯 RAG 问答", error);
                    qwenRagFallback(systemPrompt, productContext, userMessage, conversationId, emitter);
                })
                .start();
    }

    /**
     * 降级链路：qwen 纯 RAG 问答（不带工具，仅检索上下文 + qwen 生成）
     * qwen 也失败 → templateFallback（无 LLM 模板拼检索结果）
     */
    private void qwenRagFallback(String systemPrompt, String productContext,
                                 String userMessage, String conversationId, SseEmitter emitter) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(userMessage));
            streamingChatModelBackup.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    if (token != null && !token.isBlank()) {
                        sendEvent(emitter, "token", token);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    String reply = response != null && response.aiMessage() != null
                            ? response.aiMessage().text() : "";
                    saveAssistantMessage(conversationId, reply);
                    completeSse(emitter, reply);
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("qwen 备用模型也失败，降级到模板拼检索结果", error);
                    templateFallback(productContext, conversationId, emitter);
                }
            });
        } catch (Exception e) {
            log.warn("qwen 备用模型异常，降级到模板拼检索结果", e);
            templateFallback(productContext, conversationId, emitter);
        }
    }

    /**
     * 最底层降级：无 LLM，把检索结果用模板拼成文本直接返回（AI 挂了客服不崩）
     */
    private void templateFallback(String productContext, String conversationId, SseEmitter emitter) {
        String reply = (productContext != null && !productContext.isBlank())
                ? "抱歉，AI 暂时不可用，以下相关商品供您参考：\n" + productContext
                : "抱歉，AI 暂时不可用，请稍后再试";
        saveAssistantMessage(conversationId, reply);
        sendEvent(emitter, "end", reply);
        completeSse(emitter, reply);
    }

    /** 保存 AI 回复到 MySQL + 首次对话自动生成标题（主链路与降级链路共用） */
    private void saveAssistantMessage(String conversationId, String reply) {
        ChatMessageDO aiMsgDO = new ChatMessageDO();
        aiMsgDO.setConversationId(Long.valueOf(conversationId));
        aiMsgDO.setRole("assistant");
        aiMsgDO.setContent(reply);
        chatHistoryService.saveMessage(aiMsgDO);

        boolean isFirstMessage = chatHistoryService.listMessages(Long.valueOf(conversationId)).size() <= 2;
        if (isFirstMessage) {
            chatHistoryService.updateConversationTitle(Long.valueOf(conversationId));
        }
    }

    /**
     * 清理会话（对话结束或超时后调用）
     * V1 无内存记忆缓存（每轮从 MySQL store 重建窗口），清空会话历史留待 V2
     */
    public void clearConversation(String conversationId) {
        // 保留方法签名兼容 ChatController 调用，V1 无内存态可清
    }

    /**
     * 将 RAG 命中商品列表格式化为给大模型的商品上下文文本（名称/价格区间/摘要）
     *
     * @param hits RAG 检索命中结果（可为 null 或空，此时返回占位提示文本）
     * @return 拼接后的商品上下文文本
     */
    private String buildProductContext(List<RagHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return "（未找到相关商品信息）";
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

    /**
     * 构建RAG上下文的JSON字符串（命中商品ID、分数等）
     */
    private String buildRagContextJson(List<RagHitVO> hits) {
        if (hits == null || hits.isEmpty()) return null;
        try {
            List<Map<String, Object>> ctx = new ArrayList<>();
            for (RagHitVO hit : hits) {
                ctx.add(Map.of(
                        "productId", hit.getProductId(),
                        "name", hit.getName(),
                        "score", hit.getScore(),
                        "price", hit.getMinPrice()
                ));
            }
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            log.warn("构建RAG上下文JSON失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * RAG 无结果时，尝试按分类名匹配回退查询数据库
     */
    private List<RagHitVO> fallbackSearchByCategory(String userMessage) {
        try {
            List<CategoryDO> allCategories = categoryMapper.selectList(
                    new LambdaQueryWrapper<CategoryDO>()
                            .eq(CategoryDO::getStatus, 0));
            if (allCategories == null || allCategories.isEmpty()) return null;

            CategoryDO matched = null;
            for (CategoryDO cat : allCategories) {
                if (userMessage.contains(cat.getName())) {
                    matched = cat;
                    break;
                }
            }
            if (matched == null) return null;

            Long targetFirstCategoryId;
            if (matched.getParentId() != null && matched.getParentId() != 0) {
                targetFirstCategoryId = matched.getParentId();
            } else {
                targetFirstCategoryId = matched.getId();
            }

            List<ProductDO> products = productMapper.selectList(
                    new LambdaQueryWrapper<ProductDO>()
                            .eq(ProductDO::getStatus, 1)
                            .eq(ProductDO::getFirstCategoryId, targetFirstCategoryId)
                            .last("LIMIT 10"));

            if (products == null || products.isEmpty()) return null;

            List<RagHitVO> hits = new ArrayList<>();
            for (ProductDO p : products) {
                hits.add(RagHitVO.builder()
                        .score(1.0)
                        .productId(p.getId())
                        .name(p.getName())
                        .excerpt(p.getDescription() != null ? p.getDescription() : "")
                        .image(p.getMainImage())
                        .build());
            }
            return hits;

        } catch (Exception e) {
            log.warn("分类回退查询异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 向 SSE 连接发送一条事件（type/content 序列化为 JSON，事件名固定为 message）
     * <p>
     * 发送失败（连接已关闭等）仅记录警告日志，不向外抛出，避免打断流式链路。
     *
     * @param emitter SSE 发射器
     * @param type    事件类型（token/end/error）
     * @param content 事件内容（token 片段或结束/错误提示文案）
     */
    private void sendEvent(SseEmitter emitter, String type, String content) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", type, "content", content));
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(json));
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 发送失败（连接可能已关闭）: {}", e.getMessage());
        }
    }

    /** 发送结束事件并完成 SSE 连接 */
    private void completeSse(SseEmitter emitter, String reply) {
        sendEvent(emitter, "end", reply);
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
