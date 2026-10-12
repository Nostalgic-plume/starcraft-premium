package com.shop.rec.flink.config;

import lombok.Data;
import org.apache.flink.api.java.utils.ParameterTool;

import java.io.Serializable;

/**
 * 实时特征计算作业运行参数
 *
 * <p>
 * 对应《详细设计说明书》3.2 的作业初始化与 8.1 的 Flink 参数。参数由作业提交时的命令行参数提供
 * （{@code --key value} 形式），未提供时使用下列默认值；默认值与详细设计保持一致。
 *
 * @author shop
 */
@Data
public class FlinkJobConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 消息队列 ====================

    /** Kafka 集群地址（宿主机视角） */
    private String kafkaBootstrapServers = "127.0.0.1:9092";

    /** 行为事件主题 */
    private String behaviorTopic = "user-behavior";

    /** 消费组标识 */
    private String consumerGroup = "rec-feature-job";

    // ==================== 内存库 ====================

    /** 内存库地址 */
    private String redisHost = "127.0.0.1";

    /** 内存库端口 */
    private int redisPort = 6379;

    /** 内存库密码，通过环境变量或提交参数注入，不落明文 */
    private String redisPassword = "";

    /** 内存库逻辑库编号 */
    private int redisDatabase = 0;

    // ==================== 时间语义 ====================

    /** 乱序容忍时长（秒）：水位线 = 当前最大事件时间 − 该值，对应详细设计 3.2「向前推迟 10 秒」 */
    private long outOfOrdernessSeconds = 10L;

    /** 迟到事件容限（分钟）：事件时间早于水位线超过该值时丢弃 */
    private long lateToleranceMinutes = 5L;

    /** 汇总节流间隔（毫秒）：同一用户在该间隔内的多条事件合并为一次汇总 */
    private long summarizeIntervalMs = 200L;

    // ==================== 容错 ====================

    /**
     * 检查点存储路径
     *
     * <p>默认指向容器内挂载的检查点卷（compose 的 flink-checkpoints），
     * 可通过提交参数覆盖以适配不同部署形态。
     */
    private String checkpointStorage = "file:///opt/flink/checkpoints";

    /** 检查点间隔（毫秒），对应详细设计 8.1「检查点间隔 60 秒」 */
    private long checkpointIntervalMs = 60_000L;

    // ==================== 特征计算 ====================

    /** 单维度偏好分封顶阈值，对应配置项 feature.cap.threshold */
    private double capThreshold = 100D;

    /** 分钟桶状态 TTL（小时）：支撑 1min/5min/10min/1h 四档 */
    private long minuteBucketTtlHours = 2L;

    /** 小时桶状态 TTL（小时）：支撑 24h 档与 7d 的当日部分 */
    private long hourBucketTtlHours = 48L;

    /** 天桶状态 TTL（小时）：支撑 7d 的历史天数部分 */
    private long dayBucketTtlHours = 192L;

    /** 偏好特征写入内存库后的有效期（秒），对应特征有效期 8 天 */
    private long featureTtlSeconds = 8 * 24 * 3600L;

    /** 商品热度榜写入内存库后的有效期（秒），对应 1 天 */
    private long hotItemTtlSeconds = 24 * 3600L;

    /**
     * 从命令行参数构建配置
     *
     * @param args 作业提交参数（--key value 形式）
     * @return 作业配置
     */
    public static FlinkJobConfig fromArgs(String[] args) {
        ParameterTool params = ParameterTool.fromArgs(args);
        FlinkJobConfig config = new FlinkJobConfig();
        config.setKafkaBootstrapServers(params.get("kafka.bootstrap", config.getKafkaBootstrapServers()));
        config.setBehaviorTopic(params.get("kafka.topic", config.getBehaviorTopic()));
        config.setConsumerGroup(params.get("kafka.group", config.getConsumerGroup()));
        config.setRedisHost(params.get("redis.host", config.getRedisHost()));
        config.setRedisPort(params.getInt("redis.port", config.getRedisPort()));
        config.setRedisPassword(params.get("redis.password", config.getRedisPassword()));
        config.setRedisDatabase(params.getInt("redis.database", config.getRedisDatabase()));
        config.setOutOfOrdernessSeconds(params.getLong("time.outOfOrdernessSeconds", config.getOutOfOrdernessSeconds()));
        config.setLateToleranceMinutes(params.getLong("time.lateToleranceMinutes", config.getLateToleranceMinutes()));
        config.setSummarizeIntervalMs(params.getLong("time.summarizeIntervalMs", config.getSummarizeIntervalMs()));
        config.setCapThreshold(params.getDouble("feature.cap", config.getCapThreshold()));
        config.setCheckpointStorage(params.get("checkpoint.storage", config.getCheckpointStorage()));
        config.setCheckpointIntervalMs(params.getLong("checkpoint.intervalMs", config.getCheckpointIntervalMs()));
        return config;
    }
}
