package com.shop.rec.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 行为上报请求（外部接口 E-01 入参）
 *
 * <p>
 * 对应《详细设计说明书》5.1.1。用户标识不在本对象中——它必须由服务端从登录态解析，
 * 严禁由请求参数指定（防越权，见概要设计 2.2 安全性要求）。
 *
 * @author shop
 */
@Data
@Schema(description = "行为上报请求")
public class BehaviorReportDTO {

    @NotNull(message = "商品标识不能为空")
    @Positive(message = "商品标识必须大于 0")
    @Schema(description = "商品标识", example = "23")
    private Long itemId;

    @NotBlank(message = "行为类型不能为空")
    @Pattern(regexp = "^(view|add_cart|favorite|purchase)$",
            message = "行为类型取值非法，仅支持 view / add_cart / favorite / purchase")
    @Schema(description = "行为类型：view / add_cart / favorite / purchase", example = "add_cart")
    private String eventType;

    @NotBlank(message = "行为时间不能为空")
    @Schema(description = "行为发生时间，带时区 ISO 8601 格式", example = "2026-09-22T15:30:25+08:00")
    private String eventTime;

    @NotBlank(message = "事件标识不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{8,64}$", message = "事件标识仅允许字母、数字、下划线，长度 8~64")
    @Schema(description = "事件幂等标识", example = "evt_20260922_000001")
    private String eventId;

    @Min(value = 0, message = "停留时长不能为负")
    @Max(value = 1800000, message = "停留时长不能超过 1800000 毫秒")
    @Schema(description = "商品页停留时长（毫秒），可选；仅用于无效行为识别，不参与特征计算", example = "2200")
    private Integer duration;
}
