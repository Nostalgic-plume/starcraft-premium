package com.shop.rec.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品热度快照实体（M-04 写入）
 *
 * <p>
 * 保存各统计窗口（1h / 24h / 7d）下的商品热度分，在线召回读取内存库中的热度榜，
 * 本表用于离线核对与索引重建。
 *
 * @author shop
 */
@Data
@TableName("t_item_hot_score")
@Schema(description = "商品热度快照")
public class ItemHotScoreDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键标识")
    private Long id;

    @Schema(description = "商品标识")
    private Long itemId;

    @Schema(description = "统计窗口：1h/24h/7d")
    private String windowType;

    /** 金额无关的数值分，仍按项目规范使用 BigDecimal，禁止 double */
    @Schema(description = "热度分，行为加权累加值")
    private BigDecimal score;

    @Schema(description = "统计时间点")
    private LocalDateTime statTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "最近更新时间")
    private LocalDateTime updateTime;
}
