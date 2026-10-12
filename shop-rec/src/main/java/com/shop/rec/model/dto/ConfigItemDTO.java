package com.shop.rec.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 配置项（外部接口 E-03 的入参元素）
 *
 * @author shop
 */
@Data
@Schema(description = "配置项")
public class ConfigItemDTO {

    @NotBlank(message = "配置键不能为空")
    @Schema(description = "配置键", example = "rank.weight.cat")
    private String configKey;

    @NotBlank(message = "配置值不能为空")
    @Schema(description = "配置值", example = "0.6")
    private String configValue;

    @Schema(description = "值类型：int / decimal / string / json，缺省沿用库中已有类型")
    private String valueType;
}
