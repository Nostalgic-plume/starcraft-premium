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
 * <b>热度口径</b>：取 24 小时窗口的加权累计值，热度用于召回与打分的全局基准。
 *
 * <p>
 * <b>时效</b>：按 {@code heat.flushIntervalMs}（默认 200 毫秒）周期性写入热度榜，
 * 与用户偏好特征取同一档位，共同满足需求分析 4.3「行为产生后 5 秒内特征生效」。
 *
 * <p>
 * ⚠️ <b>不要用「拉长刷写周期」来抑制榜单抖动</b>：榜单的平滑来自 24 小时聚合窗口本身
 * （分值是 24 小时累加，天然平滑），刷写周期只决定陈旧度。原实现取 10 分钟，既没有让
 * 榜单更稳（平滑早已由窗口提供），又违背了 5 秒时效要求，还把下面两个缺陷藏了起来：
 * 定时器不自续导致分值永久冻结、热度归零不摘除成员导致沉寂商品滞留榜上。
 *
 * @author shop
 */
public class ItemHeatFunction extends KeyedProcessFunction<Long, BehaviorEvent, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(ItemHeatFunction.class);

    private static final long serialVersionUID = 1L;

    private static final String STATE_HOUR_BUCKETS = "itemHourBuckets";

    private static final String STATE_DAY_BUCKETS = "itemDayBuckets";

    private static final String STATE_NEXT_FLUSH = "itemNextFlushTs";

    /** 最近一次已刷写的小时桶编号：跨小时边界时 24 小时累加值会变小，必须重算 */
    private static final String STATE_LAST_FLUSH_HOUR = "itemLastFlushHour";

    /** 自上次刷写以来是否收到过行为：无新行为且未跨小时边界时累加值未变，可跳过重算 */
    private static final String STATE_PENDING_DELTA = "itemPendingDelta";

    private final FlinkJobConfig config;

    /** 小时桶：热度按 24 小时窗口统计 */
    private transient MapState<String, Double> hourBuckets;

    /** 天桶：保留以便后续扩展更长窗口的统计 */
    private transient MapState<String, Double> dayBuckets;

    private transient ValueState<Long> nextFlushTs;

    /** 最近一次刷写时的小时桶编号 */
    private transient ValueState<Long> lastFlushHour;

    /** 自上次刷写以来是否收到过行为 */
    private transient ValueState<Boolean> pendingDelta;

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
        lastFlushHour = getRuntimeContext().getState(new ValueStateDescriptor<>(STATE_LAST_FLUSH_HOUR, Long.class));
        pendingDelta = getRuntimeContext().getState(new ValueStateDescriptor<>(STATE_PENDING_DELTA, Boolean.class));

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

        pendingDelta.update(Boolean.TRUE);
        scheduleFlush(ctx);
    }

    /**
     * 定时刷新热度榜
     *
     * <p>
     * <b>定时器为何必须自续</b>：热度分是 24 小时滑窗累加值，商品停止产生行为后，
     * 其分值仍应随着小时桶滑出窗口而自然下降。若只在 {@code processElement} 中注册定时器
     * （刷完即清标记、下次有行为才重注册），商品最后一次行为之后就不再重算，分值将
     * <b>永久冻结</b>在最后一次刷写的值上——沉寂商品长期滞留榜单、活跃商品持续变动，
     * 排序会逐渐失真。故本轮刷写完成后立即注册下一轮，直至热度归零。
     *
     * @param timestamp 定时器时间戳
     * @param ctx       上下文
     * @param out       下游输出
     */
    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<Void> out) {
        Long itemId = ctx.getCurrentKey();
        try {
            nextFlushTs.clear();
            if (flushHeat(itemId)) {
                registerNextFlush(ctx, ctx.timerService().currentProcessingTime());
            }
        } catch (Exception e) {
            LOG.error("商品热度刷新失败, itemId: {}", itemId, e);
            // 刷写失败同样要自续，否则该商品热度会永久冻结在本轮失败前的值上
            try {
                registerNextFlush(ctx, ctx.timerService().currentProcessingTime());
            } catch (Exception ignored) {
                LOG.debug("热度定时器重注册失败, itemId: {}", itemId);
            }
        }
    }

    /**
     * 汇总 24 小时窗口热度并写入热度榜
     *
     * <p>
     * <b>变更门控</b>：无新行为且未跨小时边界时，24 小时累加值不可能变化，直接跳过重算。
     * 没有这道门控，每个商品每轮刷写都要读 24 个小时桶，商品数百时状态读取量会被刷写频率
     * 线性放大（670 商品 × 5 次/秒 × 24 桶 ≈ 8 万次状态读/秒），而其中绝大多数轮次读到的
     * 是同一个值。
     *
     * @param itemId 商品标识
     * @return true 表示仍有热度、需继续跟踪；false 表示热度已归零、已从榜上摘除
     */
    private boolean flushHeat(Long itemId) throws Exception {
        long nowHour = BucketKeys.hourBucket(Instant.now());
        Long flushedHour = lastFlushHour.value();
        boolean hourChanged = flushedHour == null || flushedHour != nowHour;
        boolean hasPending = Boolean.TRUE.equals(pendingDelta.value());

        if (!hourChanged && !hasPending) {
            return true;
        }

        double heat = 0D;
        for (long ts = nowHour - 23; ts <= nowHour; ts++) {
            Double value = hourBuckets.get(BucketKeys.of(BucketKeys.DIM_ITEM, itemId, ts));
            if (value != null) {
                heat += value;
            }
        }

        pendingDelta.clear();
        lastFlushHour.update(nowHour);

        if (heat <= 0D) {
            // 热度已全部滑出 24 小时窗口：必须摘除成员。只 return 不摘除会让该商品
            // 永久留在热度榜上，被热门召回当作热销商品返回
            redisWriter.removeItemHeat(itemId);
            return false;
        }

        Map<Long, Double> itemHeat = new HashMap<>(1);
        itemHeat.put(itemId, heat);
        redisWriter.writeItemHeat(itemHeat, config.getHotItemTtlSeconds());
        return true;
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
        registerNextFlush(ctx, now);
    }

    /**
     * 注册下一轮刷写定时器
     *
     * @param ctx 上下文
     * @param now 当前处理时间
     */
    private void registerNextFlush(Context ctx, long now) throws Exception {
        long target = now + config.getHeatFlushIntervalMs();
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
