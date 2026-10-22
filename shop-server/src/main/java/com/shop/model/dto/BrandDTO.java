package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 品牌新增 / 修改参数
 *
 * @author shop
 */
@Data
@Schema(description = "品牌参数")
public class BrandDTO {

    @NotBlank(message = "品牌名称不能为空")
    @Size(max = 100, message = "品牌名称不能超过 100 个字符")
    @Schema(description = "品牌名称（唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 500, message = "品牌 Logo 地址不能超过 500 个字符")
    @Schema(description = "品牌 Logo URL")
    private String logo;

    @Size(max = 500, message = "品牌描述不能超过 500 个字符")
    @Schema(description = "品牌描述")
    private String description;

    @Schema(description = "排序值，越小越靠前")
    private Integer sort;

    @Schema(description = "状态：0正常 1停用，默认 0")
    private Integer status;
}
