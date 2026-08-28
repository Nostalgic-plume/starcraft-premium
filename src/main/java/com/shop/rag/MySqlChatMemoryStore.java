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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 MySQL 的对话记忆存储（LangChain4j {@link ChatMemoryStore} 扩展点）
 *
 * 设计（复用 chat_message 表，避免双写）：
 * - getMessages：<b>优先返回 agent loop 活跃期缓存</b>（含工具往返消息），缓存为空才从 chat_message 实时读历史；
 * - updateMessages：更新 agent loop 缓存，<b>不持久化</b>（写入由 ChatHistoryService.saveMessage 负责，避免双写）；
 * - deleteMessages：清理 agent loop 缓存（ChatService 每轮对话开始时调用，确保从 DB 恢复干净历史）；
 * - SystemMessage 不落库（chat_message 仅存 user/assistant），由 ChatService 在 generate 时临时拼接单条。
 *
 * <p><b>为什么需要 agent loop 缓存（关键坑）</b>：LangChain4j AiServices 的 agent loop 每轮往返都会从
 * {@code store.getMessages} 重新读 memory（而非用 TokenWindowChatMemory 的内存窗口）。若直接查 DB，
 * 工具往返消息（AiMessage(toolCall) + ToolExecutionResultMessage）未持久化 → 每轮都看不到工具结果 →
 * LLM 反复调工具死循环（本次实测：getMessages 被调 19 次、工具调 5 次撞 maxToolCallingRoundTrips）。
 * 加内存缓存后，agent loop 内 AiServices 能读到含工具消息的最新窗口，问题消除。
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlChatMemoryStore implements ChatMemoryStore {

    /** agent loop 活跃期缓存：memoryId → 最新消息列表（含工具往返消息） */
    private final Map<Object, List<ChatMessage>> agentCache = new ConcurrentHashMap<>();

    /** 从 chat_message 表读取的历史窗口条数（user/assistant） */
    private static final int WINDOW_LIMIT = 10;

    private final ChatMessageMapper chatMessageMapper;

    /**
     * 返回该会话最新消息窗口：优先 agent loop 缓存（含工具往返消息，保证 AiServices 每轮能看到工具结果），
     * 缓存为空时从 chat_message 实时读取最近 WINDOW_LIMIT 条（user/assistant）作为历史恢复源
     *
     * @param memoryId 会话ID（Long 或 String 形式，可转换为 Long；否则视为无效返回空列表）
     * @return 正序消息列表
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> cached = agentCache.get(memoryId);
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        return readFromDb(memoryId);
    }

    /**
     * 更新 agent loop 活跃期缓存（含工具往返消息）；不持久化到 DB（由 ChatHistoryService.saveMessage 负责）
     *
     * @param memoryId 会话ID
     * @param messages 当前完整消息列表
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null) {
            return;
        }
        agentCache.put(memoryId, new ArrayList<>(messages));
    }

    /**
     * 清理 agent loop 缓存（ChatService 每轮对话开始时调用，确保下一轮从 DB 恢复干净历史，工具消息不跨轮污染）
     *
     * @param memoryId 会话ID
     */
    @Override
    public void deleteMessages(Object memoryId) {
        agentCache.remove(memoryId);
    }

    /**
     * 从 chat_message 表实时读取该会话最近 N 条（user/assistant），作为历史窗口恢复源
     * <p>
     * 服务重启后窗口自动从 MySQL 重建；读取异常时降级返回空列表。
     *
     * @param memoryId 会话ID（Long 或 String 形式，可转换为 Long；否则视为无效返回空列表）
     * @return 正序排列的最近 WINDOW_LIMIT 条 ChatMessage 列表
     */
    private List<ChatMessage> readFromDb(Object memoryId) {
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

    /**
     * 将 LangChain4j 的 memoryId（Long 或 String）安全转换为 Long
     *
     * @param id 会话ID，支持 Long 与 String（十进制数字字符串）
     * @return 转换后的 Long；类型不支持或字符串解析失败返回 null
     */
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
