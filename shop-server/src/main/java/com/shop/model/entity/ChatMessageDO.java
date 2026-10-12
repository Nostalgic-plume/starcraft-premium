package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息实体
 * 对应 chat_message 表，记录单条对话消息
 */
@Data
@TableName("chat_message")
public class ChatMessageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属会话ID */
    private Long conversationId;

    /** 角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** RAG 检索上下文明细（JSON格式） */
    private String ragContext;

    /** 消息Token数 */
    private Integer tokenCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
