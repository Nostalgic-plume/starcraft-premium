package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话会话实体
 * 对应 chat_conversation 表，记录用户每次智能客服对话
 */
@Data
@TableName("chat_conversation")
public class ChatConversationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 会话标题（首句话摘要） */
    private String title;

    /** 状态：0=进行中 1=已结束 */
    private Integer status;

    /** 消息总数 */
    private Integer totalMsgs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除：0=未删 1=已删 */
    @TableLogic
    private Integer deleted;
}
