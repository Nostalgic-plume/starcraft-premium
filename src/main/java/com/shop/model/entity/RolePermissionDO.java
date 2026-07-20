package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限关联实体（多对多）
 */
@Data
@TableName("role_permission")
@Schema(description = "角色-权限关联")
public class RolePermissionDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "角色ID")
    private Long roleId;

    @Schema(description = "权限ID")
    private Long permissionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
