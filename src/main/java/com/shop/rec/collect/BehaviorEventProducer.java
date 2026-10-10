package com.shop.rec.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.rec.config.RecKafkaProperties;
import com.shop.rec.model.dto.BehaviorEventDTO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 行为事件投递器（M-01 步骤 8）
 *
 * <p>
 * 以用户标识为消息键投递至 {@code user-behavior}，保证同一用户的事件落在同一分区局部有序
 * （对应详细设计 4.3 的消息键约定）。
 *
 * <p>
 * 生产者参数按详细设计 3.1 设置：单副本确认（acks=1）、开启幂等、重试 3 次、
 * 调用侧投递超时 50ms。此处使用原生 kafka-clients 而非 spring-kafka，
 * 以便精确控制这些参数且不引入额外的自动配置（见 pom 中依赖说明）。
 *
 * <p>
 * <b>失败语义</b>：投递失败返回 false（不抛异常），由调用方按详细设计 3.1 的异常分支
 * 降级为直接落库并告警，接口仍返回成功。
 *
 * @author shop
 */
@Slf4j
@Component
public class BehaviorEventProducer {

    private final RecKafkaProperties kafkaProperties;

    private final ObjectMapper objectMapper;

    private final KafkaProducer<String, String> producer;

    /**
     * 构造投递器并初始化 Kafka 生产者
     *
     * @param kafkaProperties 消息队列参数
     * @param objectMapper    JSON 序列化器（Spring 容器提供的实例）
     */
    public BehaviorEventProducer(RecKafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
        this.producer = buildProducer(kafkaProperties);
    }

    /**
     * 投递行为事件
     *
     * <p>同步等待确认，等待上限为配置的投递超时（默认 50ms）。本地 Kafka 正常时通常在数毫秒内返回，
     * 不影响上报接口 100ms 的响应预算。
     *
     * @param event 已补齐服务端字段的行为事件
     * @return 投递成功返回 true；超时或异常返回 false，由调用方降级为落库补偿
     */
    public boolean send(BehaviorEventDTO event) {
        try {
            String key = String.valueOf(event.getUserId());
            String value = objectMapper.writeValueAsString(event);
            producer.send(new ProducerRecord<>(kafkaProperties.getUserBehaviorTopic(), key, value))
                    .get(kafkaProperties.getDeliveryTimeoutMs(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("行为事件投递被中断, eventId: {}", event.getEventId());
            return false;
        } catch (Exception e) {
            log.warn("行为事件投递失败, eventId: {}, 原因: {}", event.getEventId(), e.getMessage());
            return false;
        }
    }

    /**
     * 关闭生产者，释放网络资源
     */
    @PreDestroy
    public void close() {
        try {
            producer.flush();
            producer.close();
        } catch (Exception e) {
            log.warn("Kafka 生产者关闭异常: {}", e.getMessage());
        }
    }

    /**
     * 依据配置构建 Kafka 生产者
     *
     * @param properties 消息队列参数
     * @return 已配置的生产者实例
     */
    private KafkaProducer<String, String> buildProducer(RecKafkaProperties properties) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // acks=all 是幂等生产者的硬性前提：Kafka 客户端在构造时校验，acks != all 直接抛 ConfigException
        // （原先设 "1"，与下面的 ENABLE_IDEMPOTENCE=true 冲突，导致应用启动即失败）。
        // 「取 1 降低等待时间」的优化不成立 —— 单机单副本时 all 等的就是那唯一副本，往返路径与 1 相同。
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // 开启幂等：配合重试避免重复写入
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, properties.getRetries());
        // 幂等模式下 in-flight 上限为 5；取 1 保证重试时分区内顺序不回退
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        // 不等批量，尽快发出：行为上报以低延迟为先，吞吐靠分区与应用侧批量落库解决
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return new KafkaProducer<>(config);
    }
}
