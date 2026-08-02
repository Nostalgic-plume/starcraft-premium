package com.shop.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ChatMessageDO;
import com.shop.model.entity.ProductDO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能客服服务
 * 基于 RAG（检索增强生成）+ 流式 SSE 输出
 * 对话记录持久化到 MySQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final OpenAiStreamingChatModel streamingChatModel;
    private final RagSearchService ragSearchService;
    private final ChatHistoryService chatHistoryService;
    private final QueryRewriter queryRewriter;
    private final ObjectMapper objectMapper;
    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;

    /** 会话记忆池：conversationId → ChatMemory（保留最近 10 轮） */
    private final Map<String, MessageWindowChatMemory> chatMemoryMap = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static final int MAX_TOKENS = 10;
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

                // 2. 获取或创建会话记忆（提前获取，为 Query 改写提供对话上下文）
                MessageWindowChatMemory chatMemory = chatMemoryMap.computeIfAbsent(
                        conversationId,
                        k -> MessageWindowChatMemory.builder()
                                .maxMessages(MAX_TOKENS)
                                .build()
                );

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

                // 8. 构建消息列表
                String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, productContext);
                chatMemory.add(new SystemMessage(systemPrompt));
                chatMemory.add(new UserMessage(userMessage));

                // 8. 流式调用大模型
                StringBuilder fullResponse = new StringBuilder();
                streamingChatModel.generate(chatMemory.messages(), new dev.langchain4j.model.StreamingResponseHandler<AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        fullResponse.append(token);
                        sendEvent(emitter, "token", token);
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        String reply = "";
                        if (response != null && response.content() != null) {
                            reply = response.content().text();
                            chatMemory.add(response.content());
                        }
                        // 保存 AI 回复到 MySQL
                        ChatMessageDO aiMsgDO = new ChatMessageDO();
                        aiMsgDO.setConversationId(Long.valueOf(conversationId));
                        aiMsgDO.setRole("assistant");
                        aiMsgDO.setContent(reply);
                        chatHistoryService.saveMessage(aiMsgDO);

                        // 首次对话时自动生成标题
                        boolean isFirstMessage = chatHistoryService.listMessages(Long.valueOf(conversationId)).size() <= 2;
                        if (isFirstMessage) {
                            chatHistoryService.updateConversationTitle(Long.valueOf(conversationId));
                        }

                        completeSse(emitter, reply);
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("大模型流式调用失败", error);
                        sendEvent(emitter, "error", "AI 回复生成失败，请稍后重试");
                        completeSse(emitter, "");
                    }
                });

            } catch (Exception e) {
                log.error("ChatService 处理异常", e);
                sendEvent(emitter, "error", "服务异常，请稍后重试");
                completeSse(emitter, "");
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
