package com.shop.controller;

import com.shop.common.Result;
import com.shop.rag.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

        // 超时 5 分钟（大模型生成可能较慢）
        SseEmitter emitter = new SseEmitter(300_000L);
        chatService.ask(conversationId, message, emitter);
        return emitter;
    }

    @PostMapping("/clear")
    @Operation(summary = "清除对话历史")
    public Result<Void> clearConversation(@RequestBody Map<String, String> body) {
        String conversationId = body.getOrDefault("conversationId", "default");
        chatService.clearConversation(conversationId);
        return Result.success();
    }
}
