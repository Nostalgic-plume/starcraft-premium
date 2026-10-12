package com.shop.rec.flink.constant;

import java.time.Instant;

/**
 * 分桶键格式与时间分桶公式
 *
 * <p>
 * 严格对应《详细设计说明书》6.1「一分钟分桶实现六档滑动窗口」：
 * <ul>
 *     <li>分钟桶时间戳：{@code floor(t / 60s)}</li>
 *     <li>小时桶时间戳：{@code floor(t / 3600s)}</li>
 *     <li>自然日时间戳：{@code floor((t + 8h) / 86400s)}，按东八区切分以避免跨日统计偏差</li>
 *     <li>分桶键：{@code {维度类型}:{维度值}:{时间戳}}</li>
 * </ul>
 *
 * <p>
 * <b>与推荐服务的共享约定</b>：本类的分桶键格式与推荐服务侧的同名常量属同一约定，
 * 两侧以常量各自定义、不做代码共享（见详细设计 3.2 工程约束）。
 *
 * <p>
 * <b>时间口径</b>：所有分桶归属一律以事件时间（eventTime）为准，不使用数据到达时刻。
 *
 * @author shop
 */
public final class BucketKeys {

    private BucketKeys() {
    }

    /** 维度类型：二级类目 */
    public static final String DIM_CATEGORY = "cat";

    /** 维度类型：品牌 */
    public static final String DIM_BRAND = "brand";

    /** 维度类型：商品（用于商品热度累计） */
    public static final String DIM_ITEM = "item";

    /** 东八区偏移秒数，用于自然日切分 */
    private static final long ZONE_OFFSET_SECONDS = 8 * 3600L;

    /** 一分钟的秒数 */
    private static final long MINUTE_SECONDS = 60L;

    /** 一小时的秒数 */
    private static final long HOUR_SECONDS = 3600L;

    /** 一天的秒数 */
    private static final long DAY_SECONDS = 86400L;

    /**
     * 构造分桶键
     *
     * @param dimType   维度类型，取值见本类 DIM_* 常量
     * @param dimValue  维度取值（类目标识 / 品牌标识 / 商品标识）
     * @param timestamp 桶时间戳
     * @return 分桶键，形如 {@code cat:2031:29534600}
     */
    public static String of(String dimType, long dimValue, long timestamp) {
        return dimType + ":" + dimValue + ":" + timestamp;
    }

    /**
     * 计算分钟桶时间戳
     *
     * @param eventTime 事件时间
     * @return 分钟桶时间戳
     */
    public static long minuteBucket(Instant eventTime) {
        return eventTime.getEpochSecond() / MINUTE_SECONDS;
    }

    /**
     * 计算小时桶时间戳
     *
     * @param eventTime 事件时间
     * @return 小时桶时间戳
     */
    public static long hourBucket(Instant eventTime) {
        return eventTime.getEpochSecond() / HOUR_SECONDS;
    }

    /**
     * 计算自然日桶时间戳（按东八区切分）
     *
     * @param eventTime 事件时间
     * @return 自然日桶时间戳
     */
    public static long dayBucket(Instant eventTime) {
        return (eventTime.getEpochSecond() + ZONE_OFFSET_SECONDS) / DAY_SECONDS;
    }
}
