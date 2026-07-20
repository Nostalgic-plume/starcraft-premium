package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体
 */
@Data
@TableName("role")
@Schema(description = "角色")
public class RoleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "角色名称")
    private String name;

    @Schema(description = "角色编码：admin（管理员）/ merchant（商家）/ user（普通用户）")
    private String code;

    @Schema(description = "角色描述")
    private String description;

    @Schema(description = "状态：0正常 1禁用")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
