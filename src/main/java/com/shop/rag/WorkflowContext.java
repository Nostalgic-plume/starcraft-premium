package com.shop.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.model.entity.ChatMessageDO;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 意图 workflow 执行上下文
 * <p>
 * 每个请求构建一份，携带本次对话的用户/会话/消息/SSE 连接，并提供发事件与收尾能力。
 * 由 {@link ChatService} 在意图路由时组装注入。
 * </p>
 *
 * @author shop
 */
@Getter
@Builder
public class WorkflowContext {

    private final String conversationId;
    private final Long userId;
    private final String userMessage;
    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final ChatHistoryService chatHistoryService;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 向 SSE 推送一条事件（事件名固定 message，type/content 序列化为 JSON）
     *
     * @param type    事件类型（token/route/products/end/error）
     * @param content 事件内容
     */
    public void sendEvent(String type, String content) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", type, "content", content));
            emitter.send(SseEmitter.event().name("message").data(json));
        } catch (IOException | IllegalStateException e) {
            // 连接可能已关闭，记录不抛出
        }
    }

    /**
     * 保存本轮用户消息与助手回复并收尾（发 token + end + 完成连接）。workflow 内部完成对话的出口。
     *
     * @param reply 完整回复文本
     */
    public void replyAndComplete(String reply) {
        saveUserMessage();
        saveAssistantMessage(reply);
        // 正文必须先以 token 帧下发：前端只在 onToken 里累积正文，且显式忽略 end 帧的 content
        // （chat.ts 注释「end 已在 done 时触发」）。只发 end 会让工作流回复永远渲染不出来
        // ——实测订单查询已答「共 9 个订单」，界面却一直转圈
        sendEvent("token", reply);
        sendEvent("end", reply);
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    /**
     * 落库本轮用户消息
     * <p>
     * workflow 命中时 ChatService 会在路由分支提前 return，走不到它自己的用户消息落库（第 7 步），
     * 因此必须在这里补存，否则该轮用户消息永久丢失：实测同一会话 user 21 条 / assistant 22 条，
     * 缺失的正是「我的订单」那句——刷新后用户发言消失，会话记忆也少一轮。
     * </p>
     */
    private void saveUserMessage() {
        ChatMessageDO userMsgDO = new ChatMessageDO();
        userMsgDO.setConversationId(Long.valueOf(conversationId));
        userMsgDO.setRole("user");
        userMsgDO.setContent(userMessage);
        chatHistoryService.saveMessage(userMsgDO);
    }

    /** 把助手回复落库（首条自动生成会话标题） */
    private void saveAssistantMessage(String reply) {
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
}
