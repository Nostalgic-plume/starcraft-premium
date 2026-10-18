package com.shop.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.shop.common.Result;
import com.shop.common.StpUserUtil;
import com.shop.model.entity.ChatConversationDO;
import com.shop.model.entity.ChatMessageDO;
import com.shop.rag.chat.ChatRateLimiter;
import com.shop.rag.chat.ChatService;
import com.shop.rag.memory.ChatFeedbackService;
import com.shop.rag.memory.ChatHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 智能客服 SSE 接口
 * 提供流式问答、对话记忆清除、会话/消息查询、反馈提交等 API
 *
 * @author shop
 */
@Slf4j
@Tag(name = "智能客服")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 单条用户消息最大长度：接入层直接拦截，避免超长输入拖垮模型与检索链路 */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final ChatFeedbackService chatFeedbackService;
    private final ChatRateLimiter chatRateLimiter;

    /**
     * 智能客服流式问答（SSE）
     * AI 客服为登录后功能，未登录抛 NotLoginException（GlobalExceptionHandler 统一返回 401）；
     * message 为空时直接返回错误事件；会话超时 300 秒
     *
     * @param body 请求体：message 为问题内容（必填，非空），conversationId 为会话ID（可空，默认 default）
     * @return SSE 事件流，逐段推送回答内容，末尾推送结束事件
     */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式问答（SSE）")
    public SseEmitter ask(@RequestBody Map<String, String> body) {
        String message = body.get("message");

        // 会话 ID 必须是数字字符串（chat_conversation.id 为 Long）。缺省时由服务端生成，
        // 不能复用 "default"——所有用户共用一个 ID 会触发「无权访问该会话」且 pending 串会话。
        String conversationId = body.get("conversationId");
        boolean generatedConversationId = conversationId == null || conversationId.isBlank()
                || "default".equals(conversationId);
        if (generatedConversationId) {
            conversationId = String.valueOf(IdUtil.getSnowflakeNextId());
        }

        // 接入层守卫：空消息 / 超长消息直接拒绝，不进模型与检索链路
        if (message == null || message.isBlank()) {
            return errorEmitter("消息不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return errorEmitter("消息过长，请精简后重试（最多 " + MAX_MESSAGE_LENGTH + " 字）");
        }

        // AI 客服为登录后功能：未登录抛 NotLoginException（GlobalExceptionHandler 统一返回 401）
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();

        // 频率限制：单用户每分钟 N 条，超限直接拒绝
        // TODO: 补内容安全（敏感词/违规内容）、Prompt 注入检测与 PII 脱敏；当前只做长度与频率限制
        if (!chatRateLimiter.allow(userId)) {
            return errorEmitter("发送太频繁，请稍后再试");
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        if (generatedConversationId) {
            // 服务端生成的会话 ID 必须先回传，前端后续轮次才能延续同一会话
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data("{\"type\":\"conversationId\",\"content\":\"" + conversationId + "\"}"));
            } catch (Exception ignored) {
            }
        }
        chatService.ask(conversationId, userId, message, emitter);
        return emitter;
    }

    /** 接入层直接拒绝时返回一个已收尾的 SSE（错误 + end），保持前端事件契约一致 */
    private SseEmitter errorEmitter(String content) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("message")
                    .data("{\"type\":\"error\",\"content\":\"" + content + "\"}"));
            emitter.send(SseEmitter.event().name("message").data("{\"type\":\"end\",\"content\":\"\"}"));
            emitter.complete();
        } catch (Exception ignored) {
        }
        return emitter;
    }

    /**
     * 清除指定会话的对话记忆
     * 用于重置聊天上下文，之后提问不再携带历史信息
     * <p>
     * 会话归属校验在 {@link ChatService#clearConversation} 内强制：conversationId 由前端传入，
     * 必须先比对登录用户再操作，否则任何请求方拿到该 ID 即可清空他人的对话历史。
     *
     * @param body 请求体：conversationId 为会话ID（可空，默认 default）
     * @return 操作成功提示
     */
    @PostMapping("/clear")
    @Operation(summary = "清除对话记忆")
    public Result<Void> clearConversation(@RequestBody Map<String, String> body) {
        // AI 客服为登录后功能：未登录抛 NotLoginException（GlobalExceptionHandler 统一返回 401）
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        String conversationId = body.getOrDefault("conversationId", "default");
        chatService.clearConversation(conversationId, userId);
        return Result.success();
    }

    /**
     * 获取当前登录用户最近的会话列表
     *
     * @param limit 返回会话数量上限，默认 20
     * @return 会话列表（按最近活跃倒序）
     */
    @GetMapping("/conversations")
    @Operation(summary = "获取用户最近会话列表")
    public Result<List<ChatConversationDO>> listConversations(
            @RequestParam(defaultValue = "20") int limit) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        List<ChatConversationDO> list = chatHistoryService.listUserConversations(userId, limit);
        return Result.success(list);
    }

    /**
     * 获取指定会话的消息列表
     * 校验会话归属：会话不存在或不属于当前用户时返回错误提示
     *
     * @param id 会话ID
     * @return 消息列表，或无权访问的错误提示
     */
    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "获取会话消息列表")
    public Result<List<ChatMessageDO>> listMessages(@PathVariable Long id) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        ChatConversationDO conv = chatHistoryService.getConversation(id);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("无权访问该会话");
        }
        List<ChatMessageDO> messages = chatHistoryService.listMessages(id);
        return Result.success(messages);
    }

    /**
     * 提交对话反馈（点赞/点踩）
     * 先验证消息归属：消息不存在或所属会话不属于当前用户时返回错误提示
     *
     * @param request 反馈请求体：messageId 为消息ID（必填），rating 为评分，feedbackText 为反馈文本（可空）
     * @return 操作成功提示，或归属校验失败的错误提示
     */
    @PostMapping("/feedback")
    @Operation(summary = "提交对话反馈")
    public Result<Void> submitFeedback(@RequestBody FeedbackRequest request) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        // 验证消息归属：消息所在的会话必须属于当前用户
        ChatMessageDO msg = chatHistoryService.getMessage(request.getMessageId());
        if (msg == null) {
            return Result.error("消息不存在");
        }
        ChatConversationDO conv = chatHistoryService.getConversation(msg.getConversationId());
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("无权为该消息提交反馈");
        }
        chatFeedbackService.submitFeedback(request.getMessageId(), userId,
                request.getRating(), request.getFeedbackText());
        return Result.success();
    }

    @Data
    public static class FeedbackRequest {
        private Long messageId;
        private Integer rating;
        private String feedbackText;
    }
}
