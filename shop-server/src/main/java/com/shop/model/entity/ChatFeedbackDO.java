package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话反馈实体
 * 对应 chat_feedback 表，记录用户对AI回答的评分反馈
 */
@Data
@TableName("chat_feedback")
public class ChatFeedbackDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对应的 chat_message.id */
    private Long messageId;

    /** 用户ID */
    private Long userId;

    /** 评分：1=有用 0=无用 -1=错误 */
    private Integer rating;

    /** 用户反馈文字 */
    private String feedbackText;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
