package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类新增 / 修改参数
 * <p>
 * 分类是两级结构：parentId = 0 为一级分类，否则为某个一级分类下的二级分类。
 *
 * @author shop
 */
@Data
@Schema(description = "分类参数")
public class CategoryDTO {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称不能超过 50 个字符")
    @Schema(description = "分类名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "父分类 ID：0（或空）为一级分类")
    private Long parentId;

    @Schema(description = "排序值，越小越靠前")
    private Integer sort;

    @Size(max = 500, message = "图标地址不能超过 500 个字符")
    @Schema(description = "分类图标 URL")
    private String icon;

    @Schema(description = "状态：0正常 1停用，默认 0")
    private Integer status;
}
