package com.shop.rec.flink.function;

import com.shop.rec.flink.config.FlinkJobConfig;
import com.shop.rec.flink.constant.BehaviorWeights;
import com.shop.rec.flink.constant.BucketKeys;
import com.shop.rec.flink.model.BehaviorEvent;
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
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * 商品热度累计算子（M-04）
 *
 * <p>
 * 与用户偏好算子同处一个作业：将行为源流按商品标识二次键控，复用 6.1 的分桶公式
 * （维度类型取 item）累计商品热度，周期性写入内存库热度榜（对应详细设计 3.4 步骤 5、6）。
 *
 * <p>
 * 放在同一作业而非独立消费的原因：热度累计与用户偏好共用同一份行为流与同一套分桶公式，
 * 同作业可避免重复消费与口径分叉（见详细设计 3.4「运行形态」）。
 *
 * <p>
 * <b>热度口径</b>：取 24 小时窗口的加权累计值。热度用于召回与打分的全局基准，
 * 采用较长窗口可抑制瞬时抖动对榜单的影响。
 *
 * @author shop
 */
public class ItemHeatFunction extends KeyedProcessFunction<Long, BehaviorEvent, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(ItemHeatFunction.class);

    private static final long serialVersionUID = 1L;

    /** 热度榜刷新间隔（毫秒）：对应详细设计 3.4「每 10 分钟刷新商品热度榜」 */
    private static final long FLUSH_INTERVAL_MS = 10 * 60 * 1000L;

    private static final String STATE_HOUR_BUCKETS = "itemHourBuckets";

    private static final String STATE_DAY_BUCKETS = "itemDayBuckets";

    private static final String STATE_NEXT_FLUSH = "itemNextFlushTs";

    private final FlinkJobConfig config;

    /** 小时桶：热度按 24 小时窗口统计 */
    private transient MapState<String, Double> hourBuckets;

    /** 天桶：保留以便后续扩展更长窗口的统计 */
    private transient MapState<String, Double> dayBuckets;

    private transient ValueState<Long> nextFlushTs;

    private transient RedisWriter redisWriter;

    private transient Counter invalidEventCounter;

    public ItemHeatFunction(FlinkJobConfig config) {
        this.config = config;
    }

    @Override
    public void open(Configuration parameters) {
        hourBuckets = getRuntimeContext().getMapState(
                withTtl(new MapStateDescriptor<>(STATE_HOUR_BUCKETS, String.class, Double.class),
                        config.getHourBucketTtlHours()));
        dayBuckets = getRuntimeContext().getMapState(
                withTtl(new MapStateDescriptor<>(STATE_DAY_BUCKETS, String.class, Double.class),
                        config.getDayBucketTtlHours()));
        nextFlushTs = getRuntimeContext().getState(new ValueStateDescriptor<>(STATE_NEXT_FLUSH, Long.class));

        redisWriter = new RedisWriter(config);
        redisWriter.connect();

        invalidEventCounter = getRuntimeContext().getMetricGroup().counter("itemHeatInvalidEvent");
    }

    @Override
    public void close() {
        if (redisWriter != null) {
            redisWriter.close();
        }
    }

    /**
     * 累加商品热度
     *
     * @param event 行为事件
     * @param ctx   上下文
     * @param out   下游输出（无）
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
        double eventScore = BehaviorWeights.scoreOf(event.getEventType());
        if (eventScore <= 0D) {
            return;
        }

        long hourTs = BucketKeys.hourBucket(eventTime);
        long dayTs = BucketKeys.dayBucket(eventTime);
        String hourKey = BucketKeys.of(BucketKeys.DIM_ITEM, event.getItemId(), hourTs);
        String dayKey = BucketKeys.of(BucketKeys.DIM_ITEM, event.getItemId(), dayTs);

        Double hourValue = hourBuckets.get(hourKey);
        hourBuckets.put(hourKey, (hourValue == null ? 0D : hourValue) + eventScore);

        Double dayValue = dayBuckets.get(dayKey);
        dayBuckets.put(dayKey, (dayValue == null ? 0D : dayValue) + eventScore);

        scheduleFlush(ctx);
    }

    /**
     * 定时刷新热度榜
     *
     * @param timestamp 定时器时间戳
     * @param ctx       上下文
     * @param out       下游输出
     */
    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<Void> out) {
        Long itemId = ctx.getCurrentKey();
        try {
            flushHeat(itemId);
        } catch (Exception e) {
            LOG.error("商品热度刷新失败, itemId: {}", itemId, e);
        } finally {
            try {
                nextFlushTs.clear();
            } catch (Exception ignored) {
                LOG.debug("清理热度定时器状态失败, itemId: {}", itemId);
            }
        }
    }

    /**
     * 汇总 24 小时窗口热度并写入热度榜
     *
     * @param itemId 商品标识
     */
    private void flushHeat(Long itemId) throws Exception {
        long nowHour = BucketKeys.hourBucket(Instant.now());
        double heat = 0D;
        for (long ts = nowHour - 23; ts <= nowHour; ts++) {
            Double value = hourBuckets.get(BucketKeys.of(BucketKeys.DIM_ITEM, itemId, ts));
            if (value != null) {
                heat += value;
            }
        }
        if (heat <= 0D) {
            return;
        }
        Map<Long, Double> itemHeat = new HashMap<>(1);
        itemHeat.put(itemId, heat);
        redisWriter.writeItemHeat(itemHeat, config.getHotItemTtlSeconds());
    }

    /**
     * 注册刷新定时器（每个商品在刷新间隔内只注册一次）
     *
     * @param ctx 上下文
     */
    private void scheduleFlush(Context ctx) throws Exception {
        long now = ctx.timerService().currentProcessingTime();
        Long scheduled = nextFlushTs.value();
        if (scheduled != null && scheduled > now) {
            return;
        }
        long target = now + FLUSH_INTERVAL_MS;
        ctx.timerService().registerProcessingTimeTimer(target);
        nextFlushTs.update(target);
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
}
