package com.shop.rec.tools;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 行为数据灌数脚本（离线灌数，对应详细设计 8.5）
 *
 * <p>
 * 生成 100 万条合成行为事件并直接写入 Kafka 主题 {@code user-behavior}（{@code source=1}），
 * 绕过上报接口以规避限频与采集侧过滤，专用于数据准备。不随应用打包，作为独立工具运行：
 *
 * <pre>
 * java -cp shop-rec-1.0.0.jar:... com.shop.rec.tools.BehaviorDataGenerator
 * # 或在 IDE 中直接运行 main
 * </pre>
 *
 * <p>
 * <b>为什么必须按事件时间升序灌入（本脚本的核心约束）</b>：
 * 计算侧的水位线取「当前最大事件时间 − 10 秒」，早于水位线 5 分钟以上的事件会被判为严重迟到并丢弃。
 * 若乱序灌入，任意一条时间靠后的事件都会把水位线推高，此后所有更早的历史事件全部被丢弃——
 * 表现为行为明细条数与消息消费都正常，但特征覆盖率趋近于 0、灌数校验直接失败，
 * 排查时极易误判为特征计算故障。
 *
 * <p>
 * 本脚本因此采用「时间戳只增不减」的生成方式：从起始时刻出发，每次累加一个随机的正间隔，
 * 天然保证事件时间严格递增，无需事后排序；发送前再做一次单调性断言，作为最后一道防线。
 *
 * <p>
 * 灌数口径（详细设计 8.5）：覆盖全部商品；1 万用户 × 人均 100 条 ≈ 100 万条；
 * 行为分布 浏览 80% / 加购 10% / 收藏 6% / 购买 4%；事件时间落近 7 天且覆盖六档窗口；
 * 分批 5000 条、约 2000 条/秒。
 *
 * @author shop
 */
public final class BehaviorDataGenerator {

    /** 用户规模 */
    private static final int USER_COUNT = 10000;

    /** 人均行为条数 */
    private static final int EVENTS_PER_USER = 100;

    /** 总事件数 */
    private static final int TOTAL_EVENTS = USER_COUNT * EVENTS_PER_USER;

    /** 单批发送条数 */
    private static final int BATCH_SIZE = 5000;

    /** 目标发送速率（条/秒） */
    private static final int RATE_PER_SECOND = 2000;

    /** 数据时间跨度：近 7 天 */
    private static final long SPAN_SECONDS = 7L * 24 * 3600;

    /** 东八区时区偏移 */
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    /** 行为类型与累计分布（浏览 80% / 加购 10% / 收藏 6% / 购买 4%） */
    private static final String[] EVENT_TYPES = {"view", "add_cart", "favorite", "purchase"};

    private static final int[] EVENT_TYPE_CUMULATIVE = {80, 90, 96, 100};

    private static final String KAFKA_TOPIC = "user-behavior";

    private BehaviorDataGenerator() {
    }

    /**
     * 脚本入口
     *
     * @param args 可选参数：[0] Kafka 地址，[1] 商城库 JDBC URL，[2] 数据库账号，[3] 密码
     * @throws Exception 生成或发送失败
     */
    public static void main(String[] args) throws Exception {
        String bootstrapServers = args.length > 0 ? args[0] : "127.0.0.1:9092";
        String jdbcUrl = args.length > 1 ? args[1]
                : "jdbc:mysql://localhost:3306/shop?useUnicode=true&characterEncoding=utf-8"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        String username = args.length > 2 ? args[2] : System.getenv().getOrDefault("DB_USERNAME", "root");
        String password = args.length > 3 ? args[3] : System.getenv("DB_PASSWORD");

        List<Long> itemIds = loadSellableItemIds(jdbcUrl, username, password);
        if (itemIds.isEmpty()) {
            System.err.println("未查询到可售商品，无法生成行为数据");
            return;
        }
        System.out.printf("可售商品数: %d，开始生成 %d 条行为事件...%n", itemIds.size(), TOTAL_EVENTS);

        List<GeneratedEvent> events = generateEvents(itemIds);
        assertMonotonic(events);
        System.out.printf("事件生成完成，时间跨度: %s ~ %s%n",
                formatTime(events.get(0).eventTimeMillis),
                formatTime(events.get(events.size() - 1).eventTimeMillis));

        int sent = sendToKafka(bootstrapServer(bootstrapServers), events);
        System.out.printf("灌数完成，已发送 %d 条。建议等待 60 秒让特征计算收敛后再做校验。%n", sent);
    }

    /**
     * 查询可售商品标识
     *
     * @param jdbcUrl  JDBC 连接串
     * @param username 账号
     * @param password 密码
     * @return 商品标识列表
     * @throws Exception 查询失败
     */
    private static List<Long> loadSellableItemIds(String jdbcUrl, String username, String password) throws Exception {
        List<Long> itemIds = new ArrayList<>();
        String sql = "SELECT p.id FROM product p WHERE p.deleted = 0 AND p.status = 1 "
                + "AND EXISTS (SELECT 1 FROM sku s WHERE s.product_id = p.id AND s.deleted = 0 AND s.stock > 0)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                itemIds.add(resultSet.getLong(1));
            }
        }
        return itemIds;
    }

    /**
     * 生成事件序列
     *
     * <p>时间戳从「当前时间往前 7 天」开始按随机正间隔递增，天然严格升序。
     *
     * @param itemIds 商品标识列表
     * @return 事件列表（按事件时间升序）
     */
    private static List<GeneratedEvent> generateEvents(List<Long> itemIds) {
        Random random = new Random(20261012L);
        List<GeneratedEvent> events = new ArrayList<>(TOTAL_EVENTS);

        long startMillis = Instant.now().minusSeconds(SPAN_SECONDS).toEpochMilli();
        long spanMillis = SPAN_SECONDS * 1000L;
        // 平均间隔：保证 TOTAL_EVENTS 条事件铺满整个时间跨度
        long averageInterval = Math.max(1L, spanMillis / TOTAL_EVENTS);

        long currentMillis = startMillis;
        long sequence = 0L;
        for (int userIndex = 0; userIndex < USER_COUNT; userIndex++) {
            long userId = 100000L + userIndex;
            for (int i = 0; i < EVENTS_PER_USER; i++) {
                // 只增不减的间隔：保证事件时间严格递增（水位线不受乱序冲击）
                currentMillis += 1 + (long) (random.nextDouble() * averageInterval * 2);
                String eventType = pickEventType(random.nextInt(100));
                Long itemId = itemIds.get(random.nextInt(itemIds.size()));
                Integer duration = "view".equals(eventType)
                        ? 1000 + random.nextInt(120000)   // 浏览带停留时长
                        : null;                            // 其它行为无停留时长语义
                events.add(new GeneratedEvent("evt_gen_" + (++sequence), userId, itemId,
                        eventType, currentMillis, duration));
            }
        }
        // 尾部密集：让最后 1 分钟的窗口也有样本，覆盖最短窗口档位。
        // 起点必须严格大于上一条事件时间，否则会破坏单调性（本脚本赖以成立的前提）。
        long tailBase = Math.max(currentMillis, Instant.now().toEpochMilli() - 55_000L) + 1L;
        for (int i = 0; i < 500; i++) {
            events.add(new GeneratedEvent("evt_gen_tail_" + i, 100000L + (i % USER_COUNT),
                    itemIds.get(i % itemIds.size()), "view", tailBase + i * 100L, 3000));
        }
        return events;
    }

    /**
     * 断言事件时间单调不减
     *
     * <p>灌数的最后一道防线：不满足直接中断，避免灌完才发现特征为空。
     *
     * @param events 事件列表
     */
    private static void assertMonotonic(List<GeneratedEvent> events) {
        for (int i = 1; i < events.size(); i++) {
            if (events.get(i).eventTimeMillis < events.get(i - 1).eventTimeMillis) {
                throw new IllegalStateException(String.format(
                        "事件时间非单调：第 %d 条 (%d) 早于第 %d 条 (%d)，灌数中止",
                        i, events.get(i).eventTimeMillis, i - 1, events.get(i - 1).eventTimeMillis));
            }
        }
        System.out.println("✓ 事件时间单调性断言通过");
    }

    /**
     * 分批写入 Kafka
     *
     * @param bootstrapServers Kafka 地址
     * @param events           事件列表
     * @return 实际发送条数
     * @throws Exception 发送失败
     */
    private static int sendToKafka(String bootstrapServers, List<GeneratedEvent> events) throws Exception {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 20);

        int sent = 0;
        long batchStart = System.currentTimeMillis();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            for (GeneratedEvent event : events) {
                producer.send(new ProducerRecord<>(KAFKA_TOPIC,
                        String.valueOf(event.userId), event.toJson()));
                sent++;
                if (sent % BATCH_SIZE == 0) {
                    producer.flush();
                    throttle(batchStart, sent);
                }
            }
            producer.flush();
        }
        return sent;
    }

    /**
     * 速率控制：按目标速率节流，避免一次性打满计算侧
     *
     * @param batchStartMillis 本轮起始时间
     * @param sentCount        已发送条数
     */
    private static void throttle(long batchStartMillis, int sentCount) throws InterruptedException {
        long expectedMillis = sentCount * 1000L / RATE_PER_SECOND;
        long actualMillis = System.currentTimeMillis() - batchStartMillis;
        if (actualMillis < expectedMillis) {
            TimeUnit.MILLISECONDS.sleep(expectedMillis - actualMillis);
        }
    }

    /**
     * 按累计分布选择行为类型
     *
     * @param bucket 0~99 的随机数
     * @return 行为类型
     */
    private static String pickEventType(int bucket) {
        for (int i = 0; i < EVENT_TYPE_CUMULATIVE.length; i++) {
            if (bucket < EVENT_TYPE_CUMULATIVE[i]) {
                return EVENT_TYPES[i];
            }
        }
        return EVENT_TYPES[EVENT_TYPES.length - 1];
    }

    /**
     * 规范化 Kafka 地址
     *
     * @param bootstrapServers 原始地址
     * @return Kafka 生产端地址
     */
    private static String bootstrapServer(String bootstrapServers) {
        return bootstrapServers;
    }

    /**
     * 格式化时间用于日志
     *
     * @param millis 毫秒时间戳
     * @return ISO 8601 文本
     */
    private static String formatTime(long millis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE).toString();
    }

    /**
     * 生成的单个事件
     */
    private static final class GeneratedEvent {

        private final String eventId;

        private final long userId;

        private final long itemId;

        private final String eventType;

        private final long eventTimeMillis;

        private final Integer duration;

        private GeneratedEvent(String eventId, long userId, long itemId,
                               String eventType, long eventTimeMillis, Integer duration) {
            this.eventId = eventId;
            this.userId = userId;
            this.itemId = itemId;
            this.eventType = eventType;
            this.eventTimeMillis = eventTimeMillis;
            this.duration = duration;
        }

        /**
         * 序列化为 Kafka 报文（字段与详细设计 4.3 一致）
         *
         * @return JSON 文本
         */
        private String toJson() {
            StringBuilder json = new StringBuilder(200);
            json.append("{\"eventId\":\"").append(eventId)
                    .append("\",\"userId\":").append(userId)
                    .append(",\"itemId\":").append(itemId)
                    .append(",\"eventType\":\"").append(eventType)
                    .append("\",\"eventTime\":\"").append(formatTime(eventTimeMillis))
                    .append("\",\"duration\":");
            json.append(duration == null ? "null" : duration);
            json.append(",\"source\":1,\"receivedTime\":\"").append(formatTime(System.currentTimeMillis()))
                    .append("\"}");
            return json.toString();
        }
    }
}
