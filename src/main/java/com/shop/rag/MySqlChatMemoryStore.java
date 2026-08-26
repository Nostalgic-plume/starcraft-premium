package com.shop.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.mapper.ChatMessageMapper;
import com.shop.model.entity.ChatMessageDO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 MySQL 的对话记忆存储（LangChain4j {@link ChatMemoryStore} 扩展点）
 *
 * 设计（复用 chat_message 表，避免双写）：
 * - getMessages：实时读取该会话最近 N 条（user/assistant），作为滑动窗口恢复源，
 *   服务重启后窗口自动从 chat_message 重建，无需旁路恢复逻辑；
 * - updateMessages / deleteMessages：空实现——消息写入由 ChatHistoryService.saveMessage 负责，
 *   窗口 = chat_message 最近 N 条实时计算，无需回写；
 * - SystemMessage 不落库（chat_message 仅存 user/assistant），由 ChatService 在 generate 时
 *   临时拼接单条 SystemMessage，天然避免多轮累积（防 token 爆炸）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlChatMemoryStore implements ChatMemoryStore {

    /** 窗口容量（与 MessageWindowChatMemory.maxMessages 保持一致） */
    private static final int WINDOW_LIMIT = 10;

    private final ChatMessageMapper chatMessageMapper;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            Long conversationId = toLong(memoryId);
            if (conversationId == null) {
                return Collections.emptyList();
            }
            // 倒序取最近 N 条，再转正序返回（最早的在最前）
            List<ChatMessageDO> rows = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageDO>()
                            .eq(ChatMessageDO::getConversationId, conversationId)
                            .orderByDesc(ChatMessageDO::getCreateTime)
                            .last("LIMIT " + WINDOW_LIMIT));
            List<ChatMessage> messages = new ArrayList<>(rows.size());
            for (int i = rows.size() - 1; i >= 0; i--) {
                ChatMessageDO row = rows.get(i);
                if ("user".equals(row.getRole())) {
                    messages.add(new UserMessage(row.getContent()));
                } else if ("assistant".equals(row.getRole())) {
                    messages.add(new AiMessage(row.getContent()));
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("读取会话记忆失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 空实现：消息持久化由 ChatHistoryService.saveMessage 负责，窗口实时从 chat_message 计算
    }

    @Override
    public void deleteMessages(Object memoryId) {
        // 空实现：会话消息清理由 ChatHistoryService 负责
    }

    private static Long toLong(Object id) {
        if (id instanceof Long) {
            return (Long) id;
        }
        if (id instanceof String) {
            try {
                return Long.valueOf((String) id);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
