package com.shop.rec.flink.function;

import com.shop.rec.flink.config.FlinkJobConfig;
import com.shop.rec.flink.constant.BehaviorWeights;
import com.shop.rec.flink.constant.BucketKeys;
import com.shop.rec.flink.model.BehaviorEvent;
import com.shop.rec.flink.model.PrefScore;
import com.shop.rec.flink.sink.RedisWriter;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户实时偏好计算算子（M-02）
 *
 * <p>
 * 严格对应《详细设计说明书》3.2 的业务处理步骤。核心机制：
 * <ol>
 *     <li><b>分桶状态下沉托管状态</b>：分钟桶 / 小时桶 / 天桶分别存于三个 {@link MapState}，
 *         各自配置状态 TTL（2 小时 / 48 小时 / 192 小时），由检查点保障持久化，
 *         <b>不写内存库</b>（该调整见详细设计 3.2 与 4.2）；</li>
 *     <li><b>迟到事件归入正确的时间桶</b>：分桶键含事件自身的时间戳，迟到事件天然落入其所属桶，
 *         无需补偿逻辑；</li>
 *     <li><b>汇总由处理时间定时器驱动</b>：同一用户 200 毫秒内的多条事件合并为一次汇总，
 *         不依赖水位线推进，因此最坏延迟为「事件到达 + 节流间隔 + 汇总耗时」，
 *         可稳定满足「行为产生后 5 秒内生效」；</li>
 *     <li><b>六级窗口汇总与封顶</b>：按详细设计 6.1、6.2 的公式计算窗口分、合成偏好分并双重封顶。</li>
 * </ol>
 *
 * <p>
 * <b>字段排除约定</b>：{@code duration} 仅用于无效行为识别，本算子不读取该字段，
 * 更不参与加权、窗口汇总、偏好分合成与封顶计算。
 *
 * @author shop
 */
public class UserPreferenceFunction extends KeyedProcessFunction<Long, BehaviorEvent, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(UserPreferenceFunction.class);

    private static final long serialVersionUID = 1L;

    /** 单用户单桶状态字段数上限：超过即清理最旧分桶键（对应详细设计 3.2 处理步骤 8） */
    private static final int MAX_BUCKET_FIELDS = 5000;

    /** 状态名 */
    private static final String STATE_MINUTE_BUCKETS = "minuteBuckets";

    private static final String STATE_HOUR_BUCKETS = "hourBuckets";

    private static final String STATE_DAY_BUCKETS = "dayBuckets";

    /** 分桶键分隔符 */
    private static final String KEY_SEPARATOR = ":";

    private final FlinkJobConfig config;

    /** 分钟桶：支撑 1min / 5min / 10min / 1h 四档 */
    private transient MapState<String, Double> minuteBuckets;

    /** 小时桶：支撑 24h 档与 7d 的当日部分 */
    private transient MapState<String, Double> hourBuckets;

    /** 天桶：支撑 7d 的历史天数部分 */
    private transient MapState<String, Double> dayBuckets;

    /** 下一次汇总的处理时间戳，用于节流（避免每条事件都触发全量汇总） */
    private transient ValueState<Long> nextSummarizeTs;

    private transient RedisWriter redisWriter;

    // ==================== 指标 ====================

    private transient Counter invalidEventCounter;

    private transient Counter lateDroppedCounter;

    private transient Counter missingDimensionCounter;

    private transient Counter summarizeCounter;

    public UserPreferenceFunction(FlinkJobConfig config) {
        this.config = config;
    }

    /**
     * 初始化状态描述符、状态 TTL 与内存库连接
     *
     * @param parameters 运行时配置
     */
    @Override
    public void open(Configuration parameters) {
        minuteBuckets = getRuntimeContext().getMapState(
                withTtl(new MapStateDescriptor<>(STATE_MINUTE_BUCKETS, String.class, Double.class),
                        config.getMinuteBucketTtlHours()));
        hourBuckets = getRuntimeContext().getMapState(
                withTtl(new MapStateDescriptor<>(STATE_HOUR_BUCKETS, String.class, Double.class),
                        config.getHourBucketTtlHours()));
        dayBuckets = getRuntimeContext().getMapState(
                withTtl(new MapStateDescriptor<>(STATE_DAY_BUCKETS, String.class, Double.class),
                        config.getDayBucketTtlHours()));
        nextSummarizeTs = getRuntimeContext().getState(
                new ValueStateDescriptor<>("nextSummarizeTs", Long.class));

        redisWriter = new RedisWriter(config);
        redisWriter.connect();

        invalidEventCounter = getRuntimeContext().getMetricGroup().counter("invalidEvent");
        lateDroppedCounter = getRuntimeContext().getMetricGroup().counter("lateDropped");
        missingDimensionCounter = getRuntimeContext().getMetricGroup().counter("missingDimension");
        summarizeCounter = getRuntimeContext().getMetricGroup().counter("summarize");
    }

    @Override
    public void close() {
        if (redisWriter != null) {
            redisWriter.close();
        }
    }

    /**
     * 处理单条行为事件
     *
     * @param event 行为事件
     * @param ctx   上下文
     * @param out   下游输出（本算子无下游，结果直接写内存库）
     */
    @Override
    public void processElement(BehaviorEvent event, Context ctx, Collector<Void> out) throws Exception {
        if (!event.isValid()) {
            invalidEventCounter.inc();
            return;
        }

        Instant eventTime = parseEventTime(event.getEventTime());
        if (eventTime == null) {
            invalidEventCounter.inc();
            return;
        }

        // 严重迟到判定：事件时间早于水位线超过容限即丢弃（水位线由 Flink 按乱序容忍时长推进）
        long watermark = ctx.timerService().currentWatermark();
        long lateToleranceMs = config.getLateToleranceMinutes() * 60_000L;
        if (watermark > 0 && eventTime.toEpochMilli() < watermark - lateToleranceMs) {
            lateDroppedCounter.inc();
            LOG.debug("事件严重迟到已丢弃, eventId: {}, eventTime: {}", event.getEventId(), event.getEventTime());
            return;
        }

        RedisWriter.ItemDimensionHolder dimension = resolveDimension(event.getItemId());
        if (dimension == null) {
            missingDimensionCounter.inc();
            return;
        }

        double eventScore = BehaviorWeights.scoreOf(event.getEventType());
        if (eventScore <= 0D) {
            // 未知行为类型，不参与累加（与推荐服务侧的权值口径一致）
            return;
        }

        long minuteTs = BucketKeys.minuteBucket(eventTime);
        long hourTs = BucketKeys.hourBucket(eventTime);
        long dayTs = BucketKeys.dayBucket(eventTime);

        // 一条事件展开为「类目维度」与「品牌维度」两条记录，三个桶各累加一次
        accumulate(minuteBuckets, BucketKeys.DIM_CATEGORY, dimension.getCategoryId(), minuteTs, eventScore);
        accumulate(hourBuckets, BucketKeys.DIM_CATEGORY, dimension.getCategoryId(), hourTs, eventScore);
        accumulate(dayBuckets, BucketKeys.DIM_CATEGORY, dimension.getCategoryId(), dayTs, eventScore);

        accumulate(minuteBuckets, BucketKeys.DIM_BRAND, dimension.getBrandId(), minuteTs, eventScore);
        accumulate(hourBuckets, BucketKeys.DIM_BRAND, dimension.getBrandId(), hourTs, eventScore);
        accumulate(dayBuckets, BucketKeys.DIM_BRAND, dimension.getBrandId(), dayTs, eventScore);

        scheduleSummarize(ctx);
    }

    /**
     * 定时器触发：执行窗口汇总并写出偏好特征
     *
     * <p>由处理时间定时器驱动，与水位线无关，因此不受乱序容忍时长带来的滞后影响。
     *
     * @param timestamp 定时器时间戳
     * @param ctx       上下文
     * @param out       下游输出
     */
    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<Void> out) throws Exception {
        Long userId = ctx.getCurrentKey();
        try {
            summarize(userId);
            summarizeCounter.inc();
        } catch (Exception e) {
            // 汇总失败不中断作业：本次结果丢弃，下个事件到达时会重新触发汇总
            LOG.error("偏好汇总失败, userId: {}", userId, e);
        } finally {
            nextSummarizeTs.clear();
        }
    }

    /**
     * 汇总六档窗口、合成偏好分、双重封顶后写入内存库
     *
     * @param userId 用户标识
     */
    private void summarize(Long userId) throws Exception {
        guardStateSize(minuteBuckets, STATE_MINUTE_BUCKETS, userId);
        guardStateSize(hourBuckets, STATE_HOUR_BUCKETS, userId);
        guardStateSize(dayBuckets, STATE_DAY_BUCKETS, userId);

        Instant now = Instant.now();
        long nowMinute = BucketKeys.minuteBucket(now);
        long nowHour = BucketKeys.hourBucket(now);
        long nowDay = BucketKeys.dayBucket(now);

        Set<Long> categoryValues = collectDimensionValues(BucketKeys.DIM_CATEGORY);
        Set<Long> brandValues = collectDimensionValues(BucketKeys.DIM_BRAND);

        List<PrefScore> categoryPrefs = new ArrayList<>(categoryValues.size());
        for (Long categoryId : categoryValues) {
            categoryPrefs.add(new PrefScore(categoryId,
                    preferenceScore(BucketKeys.DIM_CATEGORY, categoryId, nowMinute, nowHour, nowDay)));
        }

        List<PrefScore> brandPrefs = new ArrayList<>(brandValues.size());
        for (Long brandId : brandValues) {
            brandPrefs.add(new PrefScore(brandId,
                    preferenceScore(BucketKeys.DIM_BRAND, brandId, nowMinute, nowHour, nowDay)));
        }

        redisWriter.writeUserPreference(userId, categoryPrefs, brandPrefs,
                now.toEpochMilli(), config.getFeatureTtlSeconds());
    }

    /**
     * 按详细设计 6.1(3)(4) 与 6.2 计算某维度的偏好分
     *
     * <p>计算顺序：六档窗口分 → 单窗口封顶 → 窗口权值合成 → 维度封顶。
     *
     * @param dimType   维度类型
     * @param dimValue  维度取值
     * @param nowMinute 当前分钟桶
     * @param nowHour   当前小时桶
     * @param nowDay    当前自然日桶
     * @return 偏好分
     */
    private double preferenceScore(String dimType, long dimValue, long nowMinute, long nowHour, long nowDay) throws Exception {
        double cap = config.getCapThreshold();

        double s1min = Math.min(sumMinute(dimType, dimValue, nowMinute, nowMinute), cap);
        double s5min = Math.min(sumMinute(dimType, dimValue, nowMinute - 4, nowMinute), cap);
        double s10min = Math.min(sumMinute(dimType, dimValue, nowMinute - 9, nowMinute), cap);
        double s1h = Math.min(sumMinute(dimType, dimValue, nowMinute - 59, nowMinute), cap);
        double s24h = Math.min(sumHour(dimType, dimValue, nowHour - 23, nowHour), cap);
        // 7d = 最近 24 个小时桶 + 前 6 个自然日桶（不含当天，当天部分已由小时桶覆盖）
        double s7d = Math.min(sumHour(dimType, dimValue, nowHour - 23, nowHour)
                + sumDay(dimType, dimValue, nowDay - 6, nowDay - 1), cap);

        double score = BehaviorWeights.WINDOW_1MIN * s1min
                + BehaviorWeights.WINDOW_5MIN * s5min
                + BehaviorWeights.WINDOW_10MIN * s10min
                + BehaviorWeights.WINDOW_1H * s1h
                + BehaviorWeights.WINDOW_24H * s24h
                + BehaviorWeights.WINDOW_7D * s7d;

        return Math.min(score, cap);
    }

    /**
     * 汇总分钟桶区间
     *
     * @param dimType  维度类型
     * @param dimValue 维度取值
     * @param startTs  起始分钟桶（含）
     * @param endTs    结束分钟桶（含）
     * @return 区间累计分值
     */
    private double sumMinute(String dimType, long dimValue, long startTs, long endTs) throws Exception {
        double sum = 0D;
        for (long ts = startTs; ts <= endTs; ts++) {
            Double value = minuteBuckets.get(BucketKeys.of(dimType, dimValue, ts));
            if (value != null) {
                sum += value;
            }
        }
        return sum;
    }

    /**
     * 汇总小时桶区间
     *
     * @param dimType  维度类型
     * @param dimValue 维度取值
     * @param startTs  起始小时桶（含）
     * @param endTs    结束小时桶（含）
     * @return 区间累计分值
     */
    private double sumHour(String dimType, long dimValue, long startTs, long endTs) throws Exception {
        double sum = 0D;
        for (long ts = startTs; ts <= endTs; ts++) {
            Double value = hourBuckets.get(BucketKeys.of(dimType, dimValue, ts));
            if (value != null) {
                sum += value;
            }
        }
        return sum;
    }

    /**
     * 汇总自然日桶区间
     *
     * @param dimType  维度类型
     * @param dimValue 维度取值
     * @param startTs  起始自然日桶（含）
     * @param endTs    结束自然日桶（含）
     * @return 区间累计分值
     */
    private double sumDay(String dimType, long dimValue, long startTs, long endTs) throws Exception {
        double sum = 0D;
        for (long ts = startTs; ts <= endTs; ts++) {
            Double value = dayBuckets.get(BucketKeys.of(dimType, dimValue, ts));
            if (value != null) {
                sum += value;
            }
        }
        return sum;
    }

    /**
     * 累加指定分桶的值
     *
     * @param state    目标分桶状态
     * @param dimType  维度类型
     * @param dimValue 维度取值（无效值 0 直接跳过）
     * @param timestamp 桶时间戳
     * @param score    事件分值
     */
    private void accumulate(MapState<String, Double> state, String dimType, long dimValue,
                            long timestamp, double score) throws Exception {
        if (dimValue <= 0L) {
            return;
        }
        String key = BucketKeys.of(dimType, dimValue, timestamp);
        Double current = state.get(key);
        state.put(key, (current == null ? 0D : current) + score);
    }

    /**
     * 注册汇总定时器（节流）
     *
     * <p>同一用户在节流间隔内只注册一次；已有未触发的定时器时不重复注册。
     *
     * @param ctx 上下文
     */
    private void scheduleSummarize(Context ctx) throws Exception {
        long now = ctx.timerService().currentProcessingTime();
        Long scheduled = nextSummarizeTs.value();
        if (scheduled != null && scheduled > now) {
            return;
        }
        long target = now + config.getSummarizeIntervalMs();
        ctx.timerService().registerProcessingTimeTimer(target);
        nextSummarizeTs.update(target);
    }

    /**
     * 收集状态中出现的全部维度取值
     *
     * @param dimType 维度类型
     * @return 维度取值集合
     */
    private Set<Long> collectDimensionValues(String dimType) throws Exception {
        Set<Long> values = new HashSet<>();
        collectFrom(minuteBuckets, dimType, values);
        collectFrom(hourBuckets, dimType, values);
        collectFrom(dayBuckets, dimType, values);
        return values;
    }

    /**
     * 从单个分桶状态中提取指定维度的取值
     *
     * @param state    分桶状态
     * @param dimType  维度类型
     * @param collector 结果收集器
     */
    private void collectFrom(MapState<String, Double> state, String dimType, Set<Long> collector) throws Exception {
        for (String key : state.keys()) {
            String[] parts = key.split(KEY_SEPARATOR);
            if (parts.length == 3 && dimType.equals(parts[0])) {
                collector.add(parseLong(parts[1]));
            }
        }
    }

    /**
     * 状态容量保护：字段数超过上限时清理最旧的分桶键
     *
     * <p>分桶键以时间戳结尾，按时间戳升序即等价于按时间先后排序。
     *
     * @param state     分桶状态
     * @param stateName 状态名（用于日志）
     * @param userId    用户标识
     */
    private void guardStateSize(MapState<String, Double> state, String stateName, Long userId) throws Exception {
        List<String> keys = new ArrayList<>();
        for (String key : state.keys()) {
            keys.add(key);
        }
        if (keys.size() <= MAX_BUCKET_FIELDS) {
            return;
        }
        keys.sort(Comparator.comparingLong(this::timestampOf));
        int removeCount = keys.size() - MAX_BUCKET_FIELDS;
        for (int i = 0; i < removeCount; i++) {
            state.remove(keys.get(i));
        }
        LOG.warn("分桶状态超限已清理, state: {}, userId: {}, 原字段数: {}, 清理: {}",
                stateName, userId, keys.size(), removeCount);
    }

    /**
     * 解析分桶键末尾的时间戳
     *
     * @param bucketKey 分桶键
     * @return 时间戳；解析失败返回 0
     */
    private long timestampOf(String bucketKey) {
        String[] parts = bucketKey.split(KEY_SEPARATOR);
        return parts.length == 3 ? parseLong(parts[2]) : 0L;
    }

    /**
     * 解析商品维度映射
     *
     * <p>直接读取内存库的商品可售状态键。键不存在（商品变更后失效或尚未由 M-04 刷新）时，
     * 该事件跳过并计数——维度未知的事件无法归入任何类目 / 品牌，无法参与计算。
     *
     * @param itemId 商品标识
     * @return 维度映射；不可用时返回 null
     */
    private RedisWriter.ItemDimensionHolder resolveDimension(Long itemId) {
        return redisWriter.readItemDimension(itemId).orElse(null);
    }

    /**
     * 解析事件时间（带时区 ISO 8601）
     *
     * @param rawEventTime 原始时间字符串
     * @return 事件时间；格式非法时返回 null
     */
    private Instant parseEventTime(String rawEventTime) {
        try {
            return OffsetDateTime.parse(rawEventTime).toInstant();
        } catch (DateTimeParseException e) {
            // 容错：部分客户端可能提交不带时区的本地时间，按东八区解释
            try {
                return OffsetDateTime.parse(rawEventTime + "+08:00").toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /**
     * 为状态描述符附加 TTL 配置
     *
     * @param descriptor 状态描述符
     * @param ttlHours   TTL（小时）
     * @param <T>        描述符类型
     * @return 附加 TTL 后的描述符
     */
    private <T extends org.apache.flink.api.common.state.StateDescriptor<?, ?>> T withTtl(T descriptor, long ttlHours) {
        StateTtlConfig ttlConfig = StateTtlConfig
                .newBuilder(Time.hours(ttlHours))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .cleanupInRocksdbCompactFilter(1000L)
                .build();
        descriptor.enableTimeToLive(ttlConfig);
        return descriptor;
    }

    /**
     * 字符串转长整型，解析失败返回 0
     *
     * @param value 原始值
     * @return 数值
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 当前时间的东八区表示（供调试与日志使用）
     *
     * @return 东八区当前时间
     */
    static OffsetDateTime nowInShanghai() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }
}
