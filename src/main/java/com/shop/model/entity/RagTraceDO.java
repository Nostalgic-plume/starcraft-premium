package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 行为反馈埋点实体
 * 对应 rag_trace 表，记录用户"搜索 → 点击商品"的隐式反馈（供扩充离线评估集）
 */
@Data
@TableName("rag_trace")
public class RagTraceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户搜索词 / 推荐时的 query */
    private String query;

    /** 用户点击的商品 */
    private Long productId;

    /** 行为类型：SEARCH_CLICK（搜索结果点击）等 */
    private String action;

    /** 登录用户ID（未登录为 null） */
    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
