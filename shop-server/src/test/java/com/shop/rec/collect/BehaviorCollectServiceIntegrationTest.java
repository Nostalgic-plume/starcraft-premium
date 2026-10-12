package com.shop.rec.collect;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import com.shop.ShopApplication;
import com.shop.common.StpUserUtil;
import com.shop.rec.constant.BehaviorTypes;
import com.shop.rec.model.dto.BehaviorReportDTO;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为采集链路集成测试（M-01）
 *
 * <p>
 * 覆盖 T2 的核心验收点：正常上报受理成功且事件真实进入 Kafka；重复上报被幂等去重。
 * 使用测试专用用户标识，不与真实用户数据混淆。
 *
 * <p>
 * <b>前置条件</b>：Kafka（主题 user-behavior）与 MySQL（shop / shop_rec 库）已启动，
 * 商品 ID 1 存在。
 *
 * <p>
 * <b>必须显式指定应用上下文</b>：工程内有两个启动类，而本测试位于 {@code com.shop.rec} 包下，
 * 默认会被 {@code @SpringBootTest} 解析为推荐服务上下文（{@code RecApplication}），
 * 该上下文不加载商城侧的 Mapper（{@code ProductMapper}），导致依赖注入失败。
 * M-01 行为采集归属商城后端，故显式指定 {@code ShopApplication}。
 *
 * @author shop
 */
@SpringBootTest(classes = ShopApplication.class)
class BehaviorCollectServiceIntegrationTest {

    /** 测试用用户标识：远离真实用户 ID 区间，避免污染线上数据 */
    private static final long TEST_USER_ID = 999999L;

    private static final long TEST_ITEM_ID = 1L;

    private static final String BOOTSTRAP_SERVERS = "127.0.0.1:9092";

    private static final String TOPIC = "user-behavior";

    @Autowired
    private BehaviorCollectService behaviorCollectService;

    /**
     * 正常上报：受理成功，且事件真实投递至 user-behavior 主题
     */
    @Test
    @DisplayName("正常上报：受理成功且事件进入 Kafka")
    void reportShouldDeliverEventToKafka() {
        String eventId = "evt_it_" + System.currentTimeMillis();

        CollectReject reject = SaTokenContextMockUtil.setMockContext(() -> {
            StpUserUtil.INSTANCE.login(TEST_USER_ID);
            return behaviorCollectService.report(buildReport(eventId), "it-source");
        });

        assertNull(reject, "正常上报不应被丢弃");
        String payload = pollEvent(eventId, 15_000L);
        assertNotNull(payload, "事件应已投递至 " + TOPIC + " 主题");
        assertTrue(payload.contains(eventId), "消息体应包含事件标识");
    }

    /**
     * 重复上报：首次受理，第二次被幂等去重
     */
    @Test
    @DisplayName("重复上报：第二次被幂等去重")
    void duplicatedEventShouldBeRejected() {
        String eventId = "evt_it_dup_" + System.currentTimeMillis();

        CollectReject first = SaTokenContextMockUtil.setMockContext(() -> {
            StpUserUtil.INSTANCE.login(TEST_USER_ID);
            return behaviorCollectService.report(buildReport(eventId), null);
        });
        CollectReject second = SaTokenContextMockUtil.setMockContext(() -> {
            StpUserUtil.INSTANCE.login(TEST_USER_ID);
            return behaviorCollectService.report(buildReport(eventId), null);
        });

        assertNull(first, "首次上报应受理");
        assertEquals(CollectReject.DUPLICATED, second, "相同事件标识的第二次上报应被去重");
    }

    /**
     * 构造上报请求
     *
     * @param eventId 事件标识
     * @return 上报请求
     */
    private BehaviorReportDTO buildReport(String eventId) {
        BehaviorReportDTO dto = new BehaviorReportDTO();
        dto.setItemId(TEST_ITEM_ID);
        dto.setEventType(BehaviorTypes.VIEW);
        dto.setEventTime(OffsetDateTime.now().toString());
        dto.setEventId(eventId);
        return dto;
    }

    /**
     * 从主题中查找指定事件标识的消息
     *
     * <p>使用独立消费组 + earliest 偏移，保证能读到本次测试新写入的消息。
     *
     * @param eventId      事件标识
     * @param timeoutMillis 等待上限（毫秒）
     * @return 匹配到的消息体；超时未找到返回 null
     */
    private String pollEvent(String eventId, long timeoutMillis) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        // 每次测试用独立消费组，配合 earliest 从头读取，避免受既有偏移影响
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "it-verify-" + System.currentTimeMillis());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                for (var record : records) {
                    if (record.value() != null && record.value().contains(eventId)) {
                        return record.value();
                    }
                }
            }
        }
        return null;
    }
}
