package com.shop.rec.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 固定坑位配置实体（M-09 写入，M-07 读取）
 *
 * <p>
 * 运营干预商品位：坑位商品不参与个性化打分与重排，所有用户看到的位置一致。
 * 同一时间段内同一位置不允许重复配置（唯一索引 + 业务校验共同保证）。
 *
 * @author shop
 */
@Data
@TableName("t_rec_fixed_slot")
@Schema(description = "固定坑位配置")
public class RecFixedSlotDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键标识")
    private Long id;

    @Schema(description = "坑位位置，从 1 开始")
    private Integer slotPosition;

    @Schema(description = "坑位商品标识")
    private Long itemId;

    @Schema(description = "生效开始时间")
    private LocalDateTime startTime;

    @Schema(description = "生效结束时间")
    private LocalDateTime endTime;

    @Schema(description = "状态：0 停用，1 启用")
    private Integer status;

    @Schema(description = "配置人")
    private String operator;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
