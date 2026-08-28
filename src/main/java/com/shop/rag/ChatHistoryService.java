package com.shop.rag;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.mapper.ChatConversationMapper;
import com.shop.mapper.ChatMessageMapper;
import com.shop.model.entity.ChatConversationDO;
import com.shop.model.entity.ChatMessageDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对话历史持久化服务
 * 将对话记录存入 MySQL，支持按用户 / 会话查询
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;

    // ========== 会话 ==========

    /**
     * 根据ID查询会话
     */
    public ChatConversationDO getConversation(Long conversationId) {
        return conversationMapper.selectById(conversationId);
    }

    /**
     * 根据ID查询消息
     */
    public ChatMessageDO getMessage(Long messageId) {
        return messageMapper.selectById(messageId);
    }

    /**
     * 获取或创建会话
     */
    @Transactional
    public ChatConversationDO getOrCreateConversation(Long conversationId, Long userId) {
        ChatConversationDO conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            // 所有权检查：只能访问自己的会话
            if (!conv.getUserId().equals(userId)) {
                throw new com.shop.common.BusinessException("无权访问该会话");
            }
            return conv;
        }
        // 创建新会话
        conv = new ChatConversationDO();
        conv.setId(conversationId);
        conv.setUserId(userId);
        conv.setStatus(0);
        conv.setTotalMsgs(0);
        conversationMapper.insert(conv);
        log.debug("创建新对话会话, id={}, userId={}", conversationId, userId);
        return conv;
    }

    /**
     * 更新会话标题（取第一条用户消息的前30字）
     */
    @Transactional
    public void updateConversationTitle(Long conversationId) {
        // 取第一条用户消息作为标题
        List<ChatMessageDO> msgs = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getConversationId, conversationId)
                        .eq(ChatMessageDO::getRole, "user")
                        .orderByAsc(ChatMessageDO::getCreateTime)
                        .last("LIMIT 1"));
        if (CollUtil.isEmpty(msgs)) return;

        String title = msgs.get(0).getContent();
        if (title != null && title.length() > 30) {
            title = title.substring(0, 30) + "...";
        }
        conversationMapper.update(null,
                new LambdaUpdateWrapper<ChatConversationDO>()
                        .eq(ChatConversationDO::getId, conversationId)
                        .set(ChatConversationDO::getTitle, title));
    }

    /**
     * 查询用户最近的会话列表
     */
    public List<ChatConversationDO> listUserConversations(Long userId, int limit) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversationDO>()
                        .eq(ChatConversationDO::getUserId, userId)
                        .eq(ChatConversationDO::getStatus, 0)
                        .orderByDesc(ChatConversationDO::getUpdateTime)
                        .last("LIMIT " + limit));
    }

    // ========== 消息 ==========

    /**
     * 保存一条消息
     */
    @Transactional
    public void saveMessage(ChatMessageDO message) {
        messageMapper.insert(message);
        // 更新会话消息计数
        conversationMapper.update(null,
                new LambdaUpdateWrapper<ChatConversationDO>()
                        .eq(ChatConversationDO::getId, message.getConversationId())
                        .setSql("total_msgs = total_msgs + 1"));
    }

    /**
     * 查询会话的消息列表
     */
    public List<ChatMessageDO> listMessages(Long conversationId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getConversationId, conversationId)
                        .orderByAsc(ChatMessageDO::getCreateTime));
    }
}
