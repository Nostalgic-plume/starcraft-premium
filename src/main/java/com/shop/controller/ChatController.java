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

    @PostMapping("/clear")
    @Operation(summary = "清除对话记忆")
    public Result<Void> clearConversation(@RequestBody Map<String, String> body) {
        String conversationId = body.getOrDefault("conversationId", "default");
        chatService.clearConversation(conversationId);
        return Result.success();
    }

    @GetMapping("/conversations")
    @Operation(summary = "获取用户最近会话列表")
    public Result<List<ChatConversationDO>> listConversations(
            @RequestParam(defaultValue = "20") int limit) {
        long userId = StpUtil.getLoginIdAsLong();
        List<ChatConversationDO> list = chatHistoryService.listUserConversations(userId, limit);
        return Result.success(list);
    }

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
