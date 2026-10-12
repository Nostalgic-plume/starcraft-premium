package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品分类实体
 */
@Data
@TableName("category")
@Schema(description = "商品分类")
public class CategoryDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "分类ID")
    private Long id;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "父分类ID（0为顶级）")
    private Long parentId;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "图标URL")
    private String icon;

    @Schema(description = "状态：0正常 1隐藏")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
