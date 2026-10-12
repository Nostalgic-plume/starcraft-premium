package com.shop.rec.flink;

import com.shop.rec.flink.config.FlinkJobConfig;
import com.shop.rec.flink.function.BehaviorEventParser;
import com.shop.rec.flink.function.ItemHeatFunction;
import com.shop.rec.flink.function.UserPreferenceFunction;
import com.shop.rec.flink.model.BehaviorEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 实时特征计算作业（M-02 与 M-04 商品热度算子）
 *
 * <p>
 * 拓扑：Kafka(user-behavior) → JSON 解析 → 事件时间与水位线 → 按用户键控（M-02 偏好计算）
 * + 按商品键控（M-04 热度累计）。两条分支共用同一个 source，避免重复消费与口径分叉。
 *
 * <p>
 * <b>时间语义</b>：水位线取 {@code forBoundedOutOfOrderness(10 秒)}，用于判定"严重迟到"
 * （早于水位线超过 5 分钟即丢弃）。<b>窗口汇总不使用事件时间窗口算子</b>，而是由算子内的
 * 处理时间定时器驱动——若改用事件时间窗口，窗口必须等水位线推进才产出结果，
 * 特征生效延迟会被拉长到 10 秒以上，与"行为产生后 5 秒内生效"的要求冲突
 * （该取舍见详细设计 3.2 的专段说明）。
 *
 * <p>
 * <b>状态与容错</b>：状态后端为 RocksDB，检查点间隔 60 秒、EXACTLY_ONCE，
 * 取消作业时保留外部检查点以便恢复。
 *
 * <p>
 * <b>提交示例</b>：
 * <pre>
 * flink run -c com.shop.rec.flink.FeatureJob shop-rec-flink-1.0.0.jar \
 *   --kafka.bootstrap 127.0.0.1:9092 --redis.host 127.0.0.1 --redis.password xxx
 * </pre>
 *
 * @author shop
 */
public class FeatureJob {

    /** 两次检查点之间的最小间隔（毫秒） */
    private static final long MIN_PAUSE_BETWEEN_CHECKPOINTS_MS = 30_000L;

    /** 检查点超时（毫秒） */
    private static final long CHECKPOINT_TIMEOUT_MS = 10 * 60_000L;

    /** 默认并行度：与 Kafka 分区数对齐（详细设计 8.1） */
    private static final int DEFAULT_PARALLELISM = 3;

    /**
     * 作业入口
     *
     * @param args 提交参数（--key value 形式，见 {@link FlinkJobConfig#fromArgs}）
     * @throws Exception 作业提交或执行失败
     */
    public static void main(String[] args) throws Exception {
        FlinkJobConfig config = FlinkJobConfig.fromArgs(args);

        StreamExecutionEnvironment env = buildEnvironment(config);
        DataStream<BehaviorEvent> eventStream = buildEventStream(env, config);

        // M-02：用户类目 / 品牌实时偏好特征
        eventStream.keyBy(BehaviorEvent::getUserId)
                .process(new UserPreferenceFunction(config))
                .name("M-02-user-preference")
                .uid("user-preference")
                .setParallelism(DEFAULT_PARALLELISM);

        // M-04：商品热度累计（同作业内独立分支）
        eventStream.keyBy(BehaviorEvent::getItemId)
                .process(new ItemHeatFunction(config))
                .name("M-04-item-heat")
                .uid("item-heat")
                .setParallelism(DEFAULT_PARALLELISM);

        env.execute("rec-feature-job");
    }

    /**
     * 构建执行环境：并行度、检查点、状态后端
     *
     * @param config 作业配置
     * @return 执行环境
     */
    private static StreamExecutionEnvironment buildEnvironment(FlinkJobConfig config) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(DEFAULT_PARALLELISM);

        // 检查点：60 秒一次、恰好一次语义、取消时保留外部检查点
        env.enableCheckpointing(config.getCheckpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);
        CheckpointConfig checkpointConfig = env.getCheckpointConfig();
        checkpointConfig.setMinPauseBetweenCheckpoints(MIN_PAUSE_BETWEEN_CHECKPOINTS_MS);
        checkpointConfig.setCheckpointTimeout(CHECKPOINT_TIMEOUT_MS);
        checkpointConfig.setMaxConcurrentCheckpoints(1);
        checkpointConfig.setExternalizedCheckpointCleanup(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        // 状态后端：RocksDB 支持状态超内存落盘，适配分桶状态随活跃用户增长
        env.setStateBackend(new EmbeddedRocksDBStateBackend());
        checkpointConfig.setCheckpointStorage(config.getCheckpointStorage());

        // 水位线推进间隔
        env.getConfig().setAutoWatermarkInterval(1000L);

        return env;
    }

    /**
     * 构建事件流：Kafka 源 → JSON 解析 → 事件时间与水位线
     *
     * @param env    执行环境
     * @param config 作业配置
     * @return 已赋时间戳与水位的用户行为事件流
     */
    private static DataStream<BehaviorEvent> buildEventStream(StreamExecutionEnvironment env, FlinkJobConfig config) {
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(config.getKafkaBootstrapServers())
                .setTopics(config.getBehaviorTopic())
                .setGroupId(config.getConsumerGroup())
                // 从最新偏移开始：历史数据由离线灌数脚本按事件时间升序灌入
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        return env.fromSource(source, WatermarkStrategy.noWatermarks(), "user-behavior-source")
                .uid("kafka-source")
                .map(new BehaviorEventParser())
                .name("parse-behavior-event")
                .uid("parse-event")
                .filter(Objects::nonNull)
                .name("drop-invalid-event")
                .uid("drop-invalid")
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<BehaviorEvent>forBoundedOutOfOrderness(
                                        Duration.ofSeconds(config.getOutOfOrdernessSeconds()))
                                .withTimestampAssigner((event, recordTimestamp) -> parseEventTimeMillis(event))
                                // 分区空闲时不阻塞水位线推进，避免个别分区无数据导致汇总长期不触发
                                .withIdleness(Duration.ofMinutes(1)));
    }

    /**
     * 提取事件时间（毫秒时间戳）作为 Flink 的事件时间
     *
     * <p>解析失败时回退为当前处理时间，保证水位线仍能推进（该事件随后会被算子按非法报文处理）。
     *
     * @param event 行为事件
     * @return 事件时间毫秒值
     */
    private static long parseEventTimeMillis(BehaviorEvent event) {
        try {
            return OffsetDateTime.parse(event.getEventTime()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(event.getEventTime() + "+08:00").toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                return System.currentTimeMillis();
            }
        }
    }
}
