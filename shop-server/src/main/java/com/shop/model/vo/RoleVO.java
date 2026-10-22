package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 角色视图（含已分配的权限 ID）
 *
 * @author shop
 */
@Data
@Schema(description = "角色信息")
public class RoleVO {

    @Schema(description = "角色 ID")
    private Long id;

    @Schema(description = "角色名称")
    private String name;

    @Schema(description = "角色编码（唯一）")
    private String code;

    @Schema(description = "角色描述")
    private String description;

    @Schema(description = "状态：0正常 1停用")
    private Integer status;

    @Schema(description = "已分配的权限 ID 列表")
    private List<Long> permissionIds;
}
