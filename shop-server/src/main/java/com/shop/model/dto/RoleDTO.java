package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 角色新增 / 修改参数
 *
 * @author shop
 */
@Data
@Schema(description = "角色参数")
public class RoleDTO {

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称不能超过 50 个字符")
    @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotBlank(message = "角色编码不能为空")
    @Size(max = 50, message = "角色编码不能超过 50 个字符")
    @Schema(description = "角色编码（唯一，创建后不可修改）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @Size(max = 200, message = "角色描述不能超过 200 个字符")
    @Schema(description = "角色描述")
    private String description;

    @Schema(description = "状态：0正常 1停用，默认 0")
    private Integer status;

    @Schema(description = "权限 ID 列表（全量覆盖，空列表表示清空权限）")
    private List<Long> permissionIds;
}
