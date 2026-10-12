package com.shop.rec.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 行为事件报文（主题 user-behavior 的消息体）
 *
 * <p>
 * 对应《详细设计说明书》4.3 的报文约定，由 M-01 补齐服务端字段后投递。
 * 与上报请求的差异：userId 由服务端解析后写入（非前端上传），
 * 并补充 source（数据来源）与 receivedTime（服务端接收时间）。
 *
 * @author shop
 */
@Data
@Schema(description = "行为事件报文")
public class BehaviorEventDTO {

    @Schema(description = "事件幂等标识，与上报报文一致")
    private String eventId;

    @Schema(description = "用户标识，由服务端解析后写入")
    private Long userId;

    @Schema(description = "商品标识")
    private Long itemId;

    @Schema(description = "行为类型：view / add_cart / favorite / purchase")
    private String eventType;

    @Schema(description = "行为发生时间，带时区 ISO 8601")
    private String eventTime;

    /** 仅用于无效行为识别，禁止参与行为加权、窗口汇总、偏好分合成与封顶计算 */
    @Schema(description = "停留时长（毫秒），空值表示未采集")
    private Integer duration;

    @Schema(description = "数据来源：0 真实上报，1 离线灌数")
    private Integer source;

    @Schema(description = "服务端接收时间")
    private String receivedTime;
}
