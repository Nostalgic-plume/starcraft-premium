package com.shop.rec.flink.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 行为事件报文（主题 user-behavior 的消息体）
 *
 * <p>
 * 字段与《详细设计说明书》4.3 一致，由商城后端的行为采集模块（M-01）投递。
 * 本类为 Flink POJO（public 无参构造 + getter/setter），可直接参与 Flink 序列化。
 *
 * @author shop
 */
@Data
@NoArgsConstructor
public class BehaviorEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件幂等标识 */
    private String eventId;

    /** 用户标识（分区键） */
    private Long userId;

    /** 商品标识 */
    private Long itemId;

    /** 行为类型：view / add_cart / favorite / purchase */
    private String eventType;

    /** 行为发生时间，带时区 ISO 8601 格式（事件时间的唯一来源） */
    private String eventTime;

    /** 停留时长（毫秒），仅用于无效行为识别，禁止参与加权与窗口汇总 */
    private Integer duration;

    /** 数据来源：0 真实上报，1 离线灌数 */
    private Integer source;

    /** 服务端接收时间，仅用于链路时延统计 */
    private String receivedTime;

    /**
     * 判断事件必备字段是否完整
     *
     * <p>缺失关键字段的事件无法参与计算，由算子跳过并计数。
     *
     * @return 字段完整返回 true
     */
    public boolean isValid() {
        return userId != null && itemId != null
                && eventType != null && !eventType.isEmpty()
                && eventTime != null && !eventTime.isEmpty();
    }
}
