package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 品牌主数据实体
 *
 * <p>
 * 商品表（product）以 brand_id 关联本表；品牌名、品牌简介等展示信息统一由本表维护，
 * 避免商品表重复存储品牌名导致口径不一致。
 */
@Data
@TableName("brand")
@Schema(description = "品牌")
public class BrandDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "品牌ID")
    private Long id;

    @Schema(description = "品牌名")
    private String name;

    @Schema(description = "品牌LOGO")
    private String logo;

    @Schema(description = "品牌简介")
    private String description;

    @Schema(description = "排序（升序）")
    private Integer sort;

    @Schema(description = "状态：0正常 1停用")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
