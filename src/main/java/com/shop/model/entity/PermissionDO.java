package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限实体
 */
@Data
@TableName("permission")
@Schema(description = "权限")
public class PermissionDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "权限名称")
    private String name;

    @Schema(description = "权限编码（如 product:add）")
    private String code;

    @Schema(description = "父权限ID（0为顶级）")
    private Long parentId;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "状态：0正常 1禁用")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
