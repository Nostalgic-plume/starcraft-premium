package com.shop.rag.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ChatMessageDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.vo.OrderVO;
import com.shop.rag.memory.ChatHistoryService;
import com.shop.rag.memory.MySqlChatMemoryStore;
import com.shop.rag.resilience.CircuitBreaker;
import com.shop.rag.resilience.ToolCallLogger;
import com.shop.rag.search.QueryRewriter;
import com.shop.rag.search.RagHitVO;
import com.shop.rag.search.RagSearchResult;
import com.shop.rag.search.RagSearchService;
import com.shop.rag.search.SkuPriceResolver;
import com.shop.service.OrderService;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    /** 分类回退路径的库存过滤：与主检索链路共用「商品 → 总库存」聚合 */
    private final SkuPriceResolver skuPriceResolver;
    private final ChatIntentResolver chatIntentResolver;
    private final CancelOrderWorkflow cancelOrderWorkflow;
    private final OrderQueryWorkflow orderQueryWorkflow;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * SSE 心跳调度器：长回答期间定期下发 ping 帧，让 Nginx/浏览器知道连接仍活着。
     * 守护线程单例，不随请求创建；线程名固定便于排查。
     */
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chat-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /** SSE 心跳间隔（秒）；≤0 表示关闭。默认 15s，短于任何一层代理的空闲超时 */
    @Value("${rag.chat.heartbeat-seconds:15}")
    private int heartbeatSeconds;

    /** 对话窗口 token 预算（TokenWindowChatMemory 按此裁剪，防 token 爆炸） */
    private static final int TOKEN_BUDGET = 3000;

    /** 订单上下文最多注入的订单条数（与 OrderQueryWorkflow 的 LIST_LIMIT 对齐） */
    private static final int ORDER_CONTEXT_LIMIT = 10;
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
            - 引用推荐商品时标注编号：[1][2][3] 对应下方"相关商品信息"中的商品 1/2/3（前端卡片按此顺序显示编号）

            ## 当有订单信息时
            下方内容若包含「===== 用户订单信息 =====」段落，说明本轮问的是订单：
            - 直接依据该段落里的真实数据回答（订单号、状态、商品、件数），不要编造
            - 不要再说「看不到订单」，也不要让用户提供订单号
            - 不要推荐商品、不要追问「您想找什么类型的东西」
            - 用户想进一步处理某笔订单时，引导他直接说出订单号

            ## 当有库存提示时
            下方内容若包含「===== 商品库存提示 =====」段落，说明用户问的商品目前全部缺货：
            - 如实告知「这款商品暂时缺货」，不要推荐其他商品
            - 不要说「没有这个商品」，也不要用「没搜到，您具体想找什么」这类引导话术
            - 语气自然，可以顺带说一句补货后可以再来看看

            ## 当没有商品信息时
            （若下方已给出「用户订单信息」段落或「商品库存提示」段落，本节不适用，
            分别按对应的订单规则、库存规则回答 —— 缺货时商品列表同样是空的，
            但那是「买不到」而非「没搜到」，不可套用本节的追问话术）
            下面商品列表为空，说明目前没有匹配到具体商品，你需要：
            1. 判断用户是不是在问一个商品类别（如「手机」「电脑」「衣服」）
            2. 如果是类别名，直接追问具体要求：「手机是吧，您想要什么价位的？主要用途是？」—— 像导购一样自然引导
            3. 如果不是类别，就告知没找到，并反问具体需求：「没搜到您说的这个，您具体想找什么类型的东西？我帮您看看～」
            4. 不要道歉，不要说「抱歉」「对不起」「还没有上架」这类话，直接追问引导

            ===== 相关商品信息 =====
            %s
            =========================

            ## 订单相关能力
            - 用户可以查询自己的订单（我的订单/到哪了/买了什么/待收货等）——查订单请基于真实数据回答，不要凭记忆编造订单信息
            - 取消订单是受控流程（写操作，需用户确认），由系统统一处理：当用户要取消订单时，引导用户直接说出要取消的订单号即可，系统会完成订单校验与二次确认；你不需要、也不能直接取消订单

            记住：你是小星，不是机器人。说话要像真人导购。
            """;

    /** 闲聊意图专用 system prompt：保留人设，但明确不推销、不把闲聊硬拗成导购 */
    private static final String CHITCHAT_SYSTEM_PROMPT = """
            你是「星辰优选」商城的智能客服「小星」，性格热情耐心，像朋友一样说话。

            本轮用户是来闲聊的（问候/道谢/情绪/陪你说话），不是来找商品的：
            - 像朋友一样自然回应，语气轻松，可用表情符号（😊💪✨）适当点缀
            - 不要推荐商品、不要罗列商品、不要追问「您想找什么类型的东西」
            - 回答简洁，两三句即可，别啰嗦
            - 用户后面主动问商品时，再切回导购模式
            """;

    /**
     * 全部售罄时注入的库存提示段落，替代商品列表占位文本。
     * <p>
     * 与「（未找到相关商品信息）」的区别：那个是「真的没搜到」，这个是「有商品但买不到」。
     * 二者若共用同一段占位文本，模型会对着「有货但缺货」的情况说「没搜到您说的这个」，
     * 用户会误以为系统不认识该商品。
     */
    private static final String OUT_OF_STOCK_CONTEXT = """
            ===== 商品库存提示 =====
            用户询问的商品目前全部缺货，请如实告知「这款商品暂时缺货」。
            ========================
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
        startHeartbeat(emitter);
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

                // ===== 意图识别 + 路由（外部 workflow 内部 agent loop 的分层入口）=====
                // 意图决定走受控 workflow 还是收窄工具后的默认链路；route 事件推给前端显示
                Intent intent = chatIntentResolver.resolve(userMessage);
                sendEvent(emitter, "route", intent.code());

                boolean orderIntent = intent == Intent.ORDER_CANCEL || intent == Intent.ORDER_QUERY;
                if (orderIntent) {
                    // 订单域意图：进入受控 workflow（订单查询/取消都不走通用 agent 的自由工具池）
                    WorkflowContext workflowCtx = WorkflowContext.builder()
                            .conversationId(conversationId)
                            .userId(userId)
                            .userMessage(userMessage)
                            .emitter(emitter)
                            .objectMapper(objectMapper)
                            .chatHistoryService(chatHistoryService)
                            .build();
                    boolean handled = intent == Intent.ORDER_CANCEL
                            ? cancelOrderWorkflow.tryHandle(workflowCtx, intent)
                            : orderQueryWorkflow.tryHandle(workflowCtx, intent);
                    if (handled) {
                        return; // workflow 已产出回复并收尾
                    }
                    // 未消费：取消=反悔/转话题清 pending；查询=模糊长句 → 落回默认链路（该链路对订单只暴露只读工具）
                }

                // 3. Query 改写：利用 LLM 补全指代、优化搜索关键词（不检索的意图直接跳过，省一次 LLM 调用）
                boolean chitchat = intent == Intent.CHITCHAT;
                boolean skipRetrieval = chitchat || orderIntent;
                String searchQuery = skipRetrieval ? userMessage : queryRewriter.rewrite(userMessage, chatMemory.messages());
                // 改写前后的对照日志由 QueryRewriter 内部记录（含失败/空值回落），此处不再重复打印

                // 4. 混合 RAG 检索 TOP-3（使用改写后的 Query）；闲聊与订单意图不检索商品：
                //    闲聊整句会被当作检索词搜一圈货架；问订单同样不该塞回一堆不相关商品
                RagSearchResult searchResult = skipRetrieval
                        ? new RagSearchResult(List.of(), false)
                        : ragSearchService.searchWithAvailability(searchQuery, 3, null, null, null);
                List<RagHitVO> hits = searchResult.hits();
                if (chitchat) {
                    log.info("[客服意图] 闲聊，跳过商品检索: {}", userMessage);
                }

                // 5. RAG 无结果时，尝试按分类名回退查询数据库（使用原始消息）；不检索的意图同样跳过
                if (!skipRetrieval && hits.isEmpty()) {
                    List<RagHitVO> categoryHits = fallbackSearchByCategory(userMessage);
                    if (categoryHits != null && !categoryHits.isEmpty()) {
                        hits = categoryHits;
                        log.info("分类回退命中: {} 条, query={}", hits.size(), userMessage);
                    }
                }

                // 6. 构建模型上下文：商品检索结果；订单域额外注入 Java 侧实时查回的订单数据
                String productContext = buildProductContext(hits);
                // 两种「空结果」必须分流：候选非空但全部售罄 → 注入缺货提示，走「暂时缺货」话术；
                // 真正没搜到 → 保持原占位文本，走「没找到，您具体想找什么」的引导话术
                if (hits.isEmpty() && searchResult.allOutOfStock()) {
                    productContext = OUT_OF_STOCK_CONTEXT;
                    // 刻意不记录 query：用户输入可能含 PII，且本轮 query 已由 RagSearchService
                    // 的 [RAG指标] 日志覆盖，此处再记一遍既重复又扩大暴露面
                    log.info("[客服意图] 候选全部售罄 → 注入缺货提示");
                } else if (hits.isEmpty()) {
                    log.info("[客服意图] 未命中商品 → 走「没找到」引导话术");
                }
                if (orderIntent) {
                    String orderContext = buildOrderContext(userId);
                    if (!orderContext.isBlank()) {
                        productContext = orderContext + "\n" + productContext;
                    }
                }

                // 7. 保存用户消息到 MySQL
                ChatMessageDO userMsgDO = new ChatMessageDO();
                userMsgDO.setConversationId(Long.valueOf(conversationId));
                userMsgDO.setRole("user");
                userMsgDO.setContent(userMessage);
                userMsgDO.setRagContext(buildRagContextJson(hits));
                chatHistoryService.saveMessage(userMsgDO);

                // 8. 构建 system prompt（每轮单条最新）并加入本轮 memory；同步写入 store 缓存，
                //    确保 AiServices 第一轮从 store.getMessages 能读到含 system 的完整窗口
                String systemPrompt = chitchat
                        ? CHITCHAT_SYSTEM_PROMPT
                        : String.format(SYSTEM_PROMPT_TEMPLATE, productContext);
                chatMemory.add(new SystemMessage(systemPrompt));
                mySqlChatMemoryStore.updateMessages(conversationId, chatMemory.messages());

                // 8.5 Phase 3：RAG 命中时推送商品卡片事件（在首个 token 前，前端渲染商品卡片；
                //      无命中不推送，前端不留空白卡片）；卡片数据随后随 assistant 消息落库，刷新可恢复
                // 订单域即便回落到通用链路也不推商品卡片：用户问订单却弹商品，语义与观感都不对
                String productsJson = (hits != null && !hits.isEmpty() && !orderIntent)
                        ? buildProductsJson(hits)
                        : null;
                if (productsJson != null) {
                    sendEvent(emitter, "products", productsJson);
                }

                // 9. 生成层降级链（Phase 5 T5）：
                //    DeepSeek(带工具) → 熔断 → 备用模型纯 RAG 问答(不带工具) → 模板拼检索结果 → 提示不可用
                if (circuitBreaker.isOpen()) {
                    log.warn("DeepSeek 熔断中 → 降级到备用模型纯 RAG 问答（不调用工具）");
                    backupRagFallback(systemPrompt, productContext, userMessage, conversationId, emitter, productsJson);
                } else {
                    deepSeekAgentLoop(intent, userId, systemPrompt, productContext, chatMemory,
                            userMessage, conversationId, emitter, productsJson);
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
     * 熔断挂载：onError → onFailure()（并当场降级到备用模型）；onCompleteResponse → onSuccess()
     */
    private void deepSeekAgentLoop(Intent intent, Long userId, String systemPrompt, String productContext,
                                   TokenWindowChatMemory chatMemory, String userMessage,
                                   String conversationId, SseEmitter emitter, String productsJson) {
        // 工具按意图收窄（最小权限）：仅 ORDER_QUERY 暴露只读订单工具；PRODUCT/默认不暴露任何工具。
        // 写操作（取消订单）已剥离出 agent，只存在于 CancelOrderWorkflow 确定性代码路径。
        Object[] tools = (intent == Intent.ORDER_QUERY)
                ? new Object[]{new OrderQueryTool(userId, orderService)}
                : new Object[0];
        AiAssistant assistant = AiServices.builder(AiAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemory(chatMemory)
                .tools(tools)
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
                    // T4 可观测性：LLM token 消耗
                    if (response != null && response.tokenUsage() != null) {
                        log.info("[LLM指标] 会话={} 输入token={} 输出token={}",
                                conversationId, response.tokenUsage().inputTokenCount(),
                                response.tokenUsage().outputTokenCount());
                    }
                    saveAssistantMessage(conversationId, reply, productsJson);
                    completeSse(emitter, reply);
                })
                .onError(error -> {
                    circuitBreaker.onFailure();
                    log.error("DeepSeek 调用失败，当场降级到备用模型纯 RAG 问答", error);
                    backupRagFallback(systemPrompt, productContext, userMessage, conversationId, emitter, productsJson);
                })
                .start();
    }

    /**
     * 降级链路：备用模型纯 RAG 问答（不带工具，仅检索上下文 + 备用模型生成）
     * 备用模型也失败 → templateFallback（无 LLM 模板拼检索结果）
     */
    private void backupRagFallback(String systemPrompt, String productContext,
                                 String userMessage, String conversationId, SseEmitter emitter,
                                 String productsJson) {
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
                    // T4 可观测性：备用模型 token 消耗
                    if (response != null && response.tokenUsage() != null) {
                        log.info("[LLM指标] 会话={} 降级模型 输入token={} 输出token={}",
                                conversationId, response.tokenUsage().inputTokenCount(),
                                response.tokenUsage().outputTokenCount());
                    }
                    saveAssistantMessage(conversationId, reply, productsJson);
                    completeSse(emitter, reply);
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("备用模型也失败，降级到模板拼检索结果", error);
                    templateFallback(productContext, conversationId, emitter, productsJson);
                }
            });
        } catch (Exception e) {
            log.warn("备用模型异常，降级到模板拼检索结果", e);
            templateFallback(productContext, conversationId, emitter, productsJson);
        }
    }

    /**
     * 最底层降级：无 LLM，把检索结果用模板拼成文本直接返回（AI 挂了客服不崩）
     */
    private void templateFallback(String productContext, String conversationId, SseEmitter emitter,
                                  String productsJson) {
        String reply = (productContext != null && !productContext.isBlank())
                ? "抱歉，AI 暂时不可用，以下为系统为您查到的最新信息：\n" + productContext
                : "抱歉，AI 暂时不可用，请稍后再试";
        saveAssistantMessage(conversationId, reply, productsJson);
        sendEvent(emitter, "end", reply);
        completeSse(emitter, reply);
    }

    /** 保存 AI 回复到 MySQL + 首次对话自动生成标题（主链路与降级链路共用） */
    private void saveAssistantMessage(String conversationId, String reply, String productsJson) {
        ChatMessageDO aiMsgDO = new ChatMessageDO();
        aiMsgDO.setConversationId(Long.valueOf(conversationId));
        aiMsgDO.setRole("assistant");
        aiMsgDO.setContent(reply);
        // 商品卡数据随 assistant 消息落 ragContext，前端刷新后据此恢复卡片
        aiMsgDO.setRagContext(productsJson);
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
     * Phase 3：构建商品卡片事件 JSON（前端渲染卡片）。
     * 字段仅 productId/name/image/minPrice/maxPrice/excerpt（摘要 ≤40 字），不含 score（C3/C4）。
     */
    private String buildProductsJson(List<RagHitVO> hits) {
        if (hits == null || hits.isEmpty()) return "[]";
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (RagHitVO hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("productId", hit.getProductId());
                item.put("name", hit.getName());
                item.put("image", hit.getImage());
                item.put("minPrice", hit.getMinPrice());
                item.put("maxPrice", hit.getMaxPrice());
                item.put("excerpt", truncateText(hit.getExcerpt(), 40));
                items.add(item);
            }
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("构建商品卡片事件失败: {}", e.getMessage());
            return "[]";
        }
    }

    /** 文本截断（含省略号），卡片摘要用 */
    private static String truncateText(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
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

            // 同步剔除售罄商品：与主检索链路的库存过滤语义一致（缺失即放行）。
            // 漏掉这一步，主链路刚过滤掉的「全售罄」商品会被这条回退路径重新捞回来推给用户
            Map<Long, Integer> stockMap = skuPriceResolver.resolveStock(
                    products.stream().map(ProductDO::getId).toList());

            List<RagHitVO> hits = new ArrayList<>();
            for (ProductDO p : products) {
                Integer totalStock = stockMap.get(p.getId());
                if (totalStock != null && totalStock <= 0) {
                    continue;
                }
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
    /** 启动 SSE 心跳：定期发 ping 帧（前端忽略未知 type），防代理在"模型思考期无数据"时判空闲断流 */
    private void startHeartbeat(SseEmitter emitter) {
        if (heartbeatSeconds <= 0) {
            return;
        }
        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendEvent(emitter, "ping", ""), heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        emitter.onCompletion(() -> future.cancel(false));
        emitter.onTimeout(() -> future.cancel(false));
        emitter.onError(e -> future.cancel(false));
    }

    /**
     * 订单域确定性上下文：直接用 OrderService 只读方法取当前用户订单，拼成文本喂给模型。
     * <p>
     * 为什么不靠工具调用：降级链（备用模型纯问答、模板兜底）都不带工具，实测 DeepSeek 503 期间
     * 客服只能答「我这边看不到你的订单，得你报订单号」。把数据放进 prompt，才能保证
     * 「问订单必有真实数据」，无论最后是哪一层模型接手。
     * </p>
     */
    private String buildOrderContext(Long userId) {
        try {
            List<OrderVO> orders = orderService.myOrdersByUserId(userId, null, ORDER_CONTEXT_LIMIT);
            if (orders == null || orders.isEmpty()) {
                return "===== 用户订单信息 =====\n当前账号下没有订单。";
            }
            StringBuilder sb = new StringBuilder("===== 用户订单信息 =====\n");
            sb.append("共 ").append(orders.size()).append(" 笔（按下单时间倒序）：\n");
            for (OrderVO order : orders) {
                sb.append("· 订单 ").append(order.getOrderNo())
                        .append("｜").append(order.getStatusText());
                if (order.getItems() != null && !order.getItems().isEmpty()) {
                    OrderVO.OrderItemVO item = order.getItems().get(0);
                    String name = item.getProductName() != null ? item.getProductName() : item.getSkuName();
                    sb.append("｜").append(name);
                    if (order.getItems().size() > 1) {
                        sb.append("等").append(order.getItems().size()).append("件");
                    }
                }
                sb.append("\n");
            }
            sb.append("以上为系统实时查询结果，请直接据实回答，不要再说「看不到订单」或让用户报订单号。");
            log.info("[客服意图] 订单上下文注入: {} 笔订单", orders.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("订单上下文查询失败，本轮不注入订单数据: {}", e.getMessage());
            return "";
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
