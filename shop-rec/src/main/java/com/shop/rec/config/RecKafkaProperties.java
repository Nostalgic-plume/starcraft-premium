package com.shop.rec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐子系统消息队列参数
 *
 * <p>
 * 绑定 rec.kafka 前缀。推荐链路使用 Kafka 承载行为事件流与商品变更通知；
 * 商城原有的订单、消息链路继续使用 RocketMQ，两套互不影响（见详细设计 5.1 选型说明）。
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rec.kafka")
public class RecKafkaProperties {

    /** Kafka 集群地址（宿主机视角，对应 compose 中 EXTERNAL listener） */
    private String bootstrapServers = "127.0.0.1:9092";

    /** 行为事件主题：M-01 生产，M-02 与 M-04 消费 */
    private String userBehaviorTopic = "user-behavior";

    /**
     * 单条事件投递超时（毫秒）
     *
     * <p>对应详细设计 3.1 步骤 8 的「投递超时 50ms」。注意这是调用侧等待确认的超时，
     * 不用 Kafka 的 delivery.timeout.ms（后者下限远高于 50ms，无法表达该约束）。
     */
    private long deliveryTimeoutMs = 50L;

    /** 生产者重试次数，对应详细设计 3.1「重试 3 次」 */
    private int retries = 3;
}
