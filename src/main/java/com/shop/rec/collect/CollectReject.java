package com.shop.rec.collect;

/**
 * 行为采集的丢弃原因
 *
 * <p>
 * 对应《详细设计说明书》3.1 的异常分支表。除爬虫与无效行为外，其余原因均返回明确错误码，
 * 便于埋点统计；爬虫与无效行为返回成功码（200）——爬虫场景不向来源暴露"已被识别"的事实，
 * 无效行为则无需让前端感知。
 *
 * <p>
 * 所有丢弃原因都要计入链路指标（按原因分类计数），便于排查"上报量正常但特征覆盖率为零"
 * 这类问题。
 *
 * @author shop
 */
public enum CollectReject {

    /** 事件重复上报：幂等去重键已存在 */
    DUPLICATED(1005, "事件重复上报"),

    /** 用户级上报频率超限 */
    LIMIT_USER(1006, "上报频率超限"),

    /** 单用户单商品单行为类型上报频率超限 */
    LIMIT_ITEM(1006, "单商品上报频率超限"),

    /** 无效行为事件：停留时长过短且在极短时间内重复浏览同商品 */
    INVALID_BEHAVIOR(200, "无效行为事件"),

    /** 爬虫来源：命中爬虫判定条件，该来源浏览事件在封禁期内全部丢弃 */
    CRAWLER(200, "爬虫来源丢弃"),

    /** 采样式丢弃：采样率小于 1 时未命中采样 */
    SAMPLED(1007, "采样式丢弃");

    /** 返回给调用方的状态码 */
    private final int code;

    /** 丢弃原因描述，用于日志与指标维度 */
    private final String reason;

    CollectReject(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    /**
     * 获取返回码
     *
     * @return 状态码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取丢弃原因
     *
     * @return 原因描述
     */
    public String getReason() {
        return reason;
    }

    /**
     * 判断是否对外按受理成功返回
     *
     * <p>爬虫与无效行为返回成功码：前者不向来源暴露"已被识别"，后者无需前端感知。
     *
     * @return 按成功返回 true
     */
    public boolean isAccepted() {
        return code == 200;
    }
}
