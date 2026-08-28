package com.shop.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.common.Result;
import com.shop.model.entity.ChatConversationDO;
import com.shop.model.entity.ChatMessageDO;
import com.shop.rag.ChatFeedbackService;
import com.shop.rag.ChatHistoryService;
import com.shop.rag.ChatService;
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

    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final ChatFeedbackService chatFeedbackService;

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
        String conversationId = body.getOrDefault("conversationId", "default");

        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data("{\"type\":\"error\",\"content\":\"消息不能为空\"}"));
                emitter.send(SseEmitter.event().name("message").data("{\"type\":\"end\",\"content\":\"\"}"));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
            return emitter;
        }

        // AI 客服为登录后功能：未登录抛 NotLoginException（GlobalExceptionHandler 统一返回 401）
        long userId = StpUtil.getLoginIdAsLong();

        SseEmitter emitter = new SseEmitter(300_000L);
        chatService.ask(conversationId, userId, message, emitter);
        return emitter;
    }

    /**
     * 清除指定会话的对话记忆
     * 用于重置聊天上下文，之后提问不再携带历史信息
     *
     * @param body 请求体：conversationId 为会话ID（可空，默认 default）
     * @return 操作成功提示
     */
    @PostMapping("/clear")
    @Operation(summary = "清除对话记忆")
    public Result<Void> clearConversation(@RequestBody Map<String, String> body) {
        String conversationId = body.getOrDefault("conversationId", "default");
        chatService.clearConversation(conversationId);
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
        long userId = StpUtil.getLoginIdAsLong();
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
        long userId = StpUtil.getLoginIdAsLong();
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
        long userId = StpUtil.getLoginIdAsLong();
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
