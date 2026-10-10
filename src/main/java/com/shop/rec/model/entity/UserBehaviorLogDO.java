package com.shop.rec.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户行为明细实体（M-01 写入）
 *
 * <p>
 * 唯一记录用户行为事件，用于画像生成、特征重建与离线统计；
 * 以 event_id 唯一索引实现上报去重。
 *
 * <p>
 * <b>时间口径</b>：event_time 为行为实际发生时刻（事件时间），是窗口归属与迟到判定的唯一依据；
 * received_time 为服务端接收时刻，仅用于链路时延统计，禁止用于任何特征计算。
 *
 * @author shop
 */
@Data
@TableName("t_user_behavior_log")
@Schema(description = "用户行为明细")
public class UserBehaviorLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键标识")
    private Long id;

    @Schema(description = "上报事件的幂等标识")
    private String eventId;

    @Schema(description = "用户标识")
    private Long userId;

    @Schema(description = "商品标识")
    private Long itemId;

    @Schema(description = "行为类型：view/add_cart/favorite/purchase")
    private String eventType;

    @Schema(description = "行为发生时间（事件时间）")
    private LocalDateTime eventTime;

    /**
     * 商品页停留时长（毫秒），空值表示未采集。
     * 仅用于无效行为识别，禁止参与行为加权、窗口汇总、偏好分合成与封顶计算。
     */
    @Schema(description = "停留时长（毫秒），仅用于无效行为识别")
    private Integer duration;

    @Schema(description = "服务端接收时间")
    private LocalDateTime receivedTime;

    @Schema(description = "数据来源：0 真实上报，1 离线灌数")
    private Integer source;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "入库时间")
    private LocalDateTime createTime;
}
