package com.shop.rec.tools;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * 绕过上报接口以规避限频与采集侧过滤，专用于数据准备。同时把行为明细批量写入
 * {@code shop_rec.t_user_behavior_log}——实时特征与热度榜依赖 Kafka 那一路，用户画像
 * 依赖落库这一路，缺任一路都会出现「指标正常但结果为空」的静默失效。
 * 不随应用打包，作为独立工具运行：
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
 * 灌数口径（详细设计 8.5）：覆盖全部可售商品；1 万用户 × 人均 100 条 ≈ 100 万条；
 * 行为分布 浏览 80% / 加购 10% / 收藏 6% / 购买 4%；事件时间落近 7 天且覆盖六档窗口；
 * 分批 5000 条、约 2000 条/秒。
 *
 * <p>
 * <b>用户与商品均取自库中真实数据</b>：可售商品从 {@code product}/{@code sku} 查询、
 * 行为主体从 {@code user} 表查询。两侧都不能用硬编码标识——生成库中不存在的标识时，
 * 行为明细与消息消费等指标全部正常，但热度榜只会累到不存在的商品上、画像只会产给
 * 不存在的用户，真实登录用户的画像始终为空，属于「指标全绿、结果为空」的静默失效。
 *
 * @author shop
 */
public final class BehaviorDataGenerator {

    /** 用户规模 */
    private static final int USER_COUNT = 10000;

    /** 人均行为条数 */
    private static final int EVENTS_PER_USER = 100;

    /** 默认总事件数（未通过命令行参数指定时使用，即 100 万条） */
    private static final int DEFAULT_TOTAL_EVENTS = USER_COUNT * EVENTS_PER_USER;

    /** 单批发送条数 */
    private static final int BATCH_SIZE = 5000;

    /** 目标发送速率（条/秒） */
    private static final int RATE_PER_SECOND = 2000;

    /** 数据时间跨度：近 7 天 */
    private static final long SPAN_SECONDS = 7L * 24 * 3600;

    /** 东八区时区偏移 */
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    /** 行为明细表（推荐库）；与商城库同实例，故用限定表名在商城库连接上写入 */
    private static final String BEHAVIOR_LOG_TABLE = "shop_rec.t_user_behavior_log";

    /** 落库用的时间格式：带毫秒，与 {@code datetime(3)} 对齐 */
    private static final DateTimeFormatter SQL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 本次运行标识：并入事件标识，使重复灌数不会撞 {@code uk_event_id} */
    private static final DateTimeFormatter RUN_TAG_FORMAT =
            DateTimeFormatter.ofPattern("yyMMddHHmmss");

    /** 行为类型与累计分布（浏览 80% / 加购 10% / 收藏 6% / 购买 4%） */
    private static final String[] EVENT_TYPES = {"view", "add_cart", "favorite", "purchase"};

    private static final int[] EVENT_TYPE_CUMULATIVE = {80, 90, 96, 100};

    private static final String KAFKA_TOPIC = "user-behavior";

    private BehaviorDataGenerator() {
    }

    /**
     * 脚本入口
     *
     * @param args 可选参数：[0] Kafka 地址，[1] 商城库 JDBC URL，[2] 数据库账号，[3] 密码，
     *             [4] 事件总数（默认 100 万，小批量验证链路时传小值，如 20000）
     * @throws Exception 生成或发送失败
     */
    public static void main(String[] args) throws Exception {
        String bootstrapServers = args.length > 0 ? args[0] : "127.0.0.1:9092";
        String jdbcUrl = args.length > 1 ? args[1]
                : "jdbc:mysql://localhost:3306/shop?useUnicode=true&characterEncoding=utf-8"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        String username = args.length > 2 ? args[2] : System.getenv().getOrDefault("DB_USERNAME", "root");
        // 空字符串同样回退到环境变量：传空值只会得到一个含义不明的认证失败，
        // 而密码本就不该出现在命令行（会进进程列表与 shell 历史）
        String password = args.length > 3 && !args[3].isBlank()
                ? args[3] : System.getenv("DB_PASSWORD");

        int totalEvents = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_TOTAL_EVENTS;
        if (totalEvents <= 0) {
            throw new IllegalArgumentException("事件总数必须为正数，实际传入: " + totalEvents);
        }

        List<Long> itemIds = loadSellableItemIds(jdbcUrl, username, password);
        if (itemIds.isEmpty()) {
            System.err.println("未查询到可售商品，无法生成行为数据");
            return;
        }
        List<Long> userIds = loadUserIds(jdbcUrl, username, password);
        if (userIds.isEmpty()) {
            System.err.println("未查询到可用用户，无法生成行为数据");
            return;
        }

        // 保持「人均 100 条」的口径，按总数反推用户数；用户总数以库中实际用户数为上限，
        // 保证每个生成的行为都归属到一个真实存在的用户。
        // 注意：平均间隔由「7 天跨度 ÷ 实际总数」决定，总数变小则间隔变大，
        // 六档窗口的时间跨度覆盖不受影响，只是各档样本变稀。
        int userCount = Math.min(Math.max(1, totalEvents / EVENTS_PER_USER), userIds.size());

        System.out.printf("可售商品数: %d，真实用户数: %d（本次使用 %d），开始生成 %d 条行为事件...%n",
                itemIds.size(), userIds.size(), userCount, userCount * EVENTS_PER_USER);

        String runTag = LocalDateTime.now().format(RUN_TAG_FORMAT);
        List<GeneratedEvent> events = generateEvents(itemIds, userIds, userCount, runTag);
        assertMonotonic(events);
        System.out.printf("事件生成完成，时间跨度: %s ~ %s%n",
                formatTime(events.get(0).eventTimeMillis),
                formatTime(events.get(events.size() - 1).eventTimeMillis));

        // 两路产出缺一不可：
        //   ① Kafka → Flink → Redis（实时特征与热度榜）
        //   ② MySQL 行为明细表 → 画像批任务（UserProfileService 读该表）
        // 只做①时指标全绿但画像恒为空——该表原先只由上报接口路径写入，灌数绕过上报接口后无人落库。
        int sent = sendToKafka(bootstrapServer(bootstrapServers), events);
        System.out.printf("已写入 Kafka %d 条（实时特征路径）。%n", sent);

        int persisted = persistBehaviorLogs(jdbcUrl, username, password, events);
        System.out.printf("已写入 %s %d 条（画像数据源，source=1）。%n", BEHAVIOR_LOG_TABLE, persisted);
        System.out.println("建议等待 60 秒让特征计算收敛后再做校验；热度榜每 10 分钟刷新一次。");
    }

    /**
     * 行为明细落库（供画像批任务使用）
     *
     * <p>
     * 与 Kafka 那一路并列的第二路产出：{@code UserProfileService} 从
     * {@code t_user_behavior_log} 归纳用户偏好，该表不写入则画像恒为空。
     * 生产链路上这张表由上报接口异步落库，离线灌数绕过上报接口，必须自行补齐，
     * 否则会出现「行为明细、消息消费、特征覆盖率等指标全部正常，但画像始终为空」
     * 的静默失效。{@code source=1} 即该表为离线灌数预留的取值。
     *
     * <p>
     * 主键不自增，故先取 {@code MAX(id)} 再顺序分配；{@code event_id} 已由调用方
     * 并入本次运行标识，重复灌数不会撞 {@code uk_event_id}。
     *
     * @param jdbcUrl  JDBC 连接串（商城库；限定表名跨库写入推荐库）
     * @param username 账号
     * @param password 密码
     * @param events   待落库事件
     * @return 实际写入条数
     * @throws Exception 落库失败
     */
    private static int persistBehaviorLogs(String jdbcUrl, String username, String password,
                                           List<GeneratedEvent> events) throws Exception {
        String sql = "INSERT INTO " + BEHAVIOR_LOG_TABLE
                + " (id, event_id, user_id, item_id, event_type, event_time, duration, received_time, source)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)";
        long receivedTime = System.currentTimeMillis();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            long nextId = queryMaxBehaviorLogId(connection) + 1L;
            int[] batchResults = new int[0];
            int inserted = 0;

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (GeneratedEvent event : events) {
                    statement.setLong(1, nextId++);
                    statement.setString(2, event.eventId);
                    statement.setLong(3, event.userId);
                    statement.setLong(4, event.itemId);
                    statement.setString(5, event.eventType);
                    statement.setString(6, formatSqlTime(event.eventTimeMillis));
                    if (event.duration == null) {
                        statement.setNull(7, Types.INTEGER);
                    } else {
                        statement.setInt(7, event.duration);
                    }
                    // 离线灌数无真实接收时刻，接收时间统一记为本轮写入时刻
                    statement.setString(8, formatSqlTime(receivedTime));
                    statement.addBatch();
                    inserted++;

                    if (inserted % BATCH_SIZE == 0) {
                        batchResults = statement.executeBatch();
                    }
                }
                if (inserted % BATCH_SIZE != 0) {
                    batchResults = statement.executeBatch();
                }
            }

            for (int result : batchResults) {
                if (result == Statement.EXECUTE_FAILED) {
                    throw new IllegalStateException("行为明细批量写入存在失败行");
                }
            }
            return inserted;
        }
    }

    /**
     * 查询行为明细表当前最大主键（该表主键不自增，插入时须自行分配）
     *
     * @param connection 数据库连接
     * @return 当前最大主键；表为空时返回 0
     * @throws Exception 查询失败
     */
    private static long queryMaxBehaviorLogId(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(id), 0) FROM " + BEHAVIOR_LOG_TABLE)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    /**
     * 毫秒时间戳格式化为东八区墙钟时间文本（与 {@code datetime(3)} 列对齐）
     *
     * <p>用字符串而非 {@code Timestamp} 绑定：避免依赖 JVM 默认时区，结果不受运行环境影响。
     *
     * @param millis 毫秒时间戳
     * @return {@code yyyy-MM-dd HH:mm:ss.SSS}
     */
    private static String formatSqlTime(long millis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE).format(SQL_TIME_FORMAT);
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
     * 查询可作为行为主体的真实用户标识
     *
     * <p>
     * 必须取自 {@code user} 表而非硬编码偏移量：行为数据与用户画像都以用户标识为归属，
     * 生成库中不存在的标识会让画像产量全部落在「幽灵用户」上——热度榜照常，但真实登录
     * 用户的画像始终为空，排查时极难发现（行为条数、消费速率等指标全都正常）。
     *
     * @param jdbcUrl  JDBC 连接串
     * @param username 账号
     * @param password 密码
     * @return 用户标识列表（按 id 升序）
     * @throws Exception 查询失败
     */
    private static List<Long> loadUserIds(String jdbcUrl, String username, String password) throws Exception {
        List<Long> userIds = new ArrayList<>();
        String sql = "SELECT id FROM user WHERE deleted = 0 ORDER BY id";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                userIds.add(resultSet.getLong(1));
            }
        }
        return userIds;
    }

    /**
     * 生成事件序列
     *
     * <p>时间戳从「当前时间往前 7 天」开始按随机正间隔递增，天然严格升序。
     *
     * @param itemIds   商品标识列表
     * @param userIds   真实用户标识列表（用户标识从中按序取用）
     * @param userCount 本次使用的用户数（总数由它 × 人均条数决定）
     * @param runTag    本次运行标识（并入事件标识，保证重复灌数不撞唯一约束）
     * @return 事件列表（按事件时间升序）
     */
    private static List<GeneratedEvent> generateEvents(List<Long> itemIds, List<Long> userIds,
                                                       int userCount, String runTag) {
        Random random = new Random(20261012L);
        int totalEvents = userCount * EVENTS_PER_USER;
        List<GeneratedEvent> events = new ArrayList<>(totalEvents);

        long startMillis = Instant.now().minusSeconds(SPAN_SECONDS).toEpochMilli();
        long spanMillis = SPAN_SECONDS * 1000L;
        // 平均间隔：保证 totalEvents 条事件铺满整个时间跨度
        long averageInterval = Math.max(1L, spanMillis / totalEvents);

        long currentMillis = startMillis;
        long sequence = 0L;
        for (int userIndex = 0; userIndex < userCount; userIndex++) {
            long userId = userIds.get(userIndex);
            for (int i = 0; i < EVENTS_PER_USER; i++) {
                // 只增不减的间隔：保证事件时间严格递增（水位线不受乱序冲击）
                currentMillis += 1 + (long) (random.nextDouble() * averageInterval * 2);
                String eventType = pickEventType(random.nextInt(100));
                Long itemId = itemIds.get(random.nextInt(itemIds.size()));
                Integer duration = "view".equals(eventType)
                        ? 1000 + random.nextInt(120000)   // 浏览带停留时长
                        : null;                            // 其它行为无停留时长语义
                events.add(new GeneratedEvent("evt_" + runTag + "_" + (++sequence), userId, itemId,
                        eventType, currentMillis, duration));
            }
        }
        // 尾部密集：让最后 1 分钟的窗口也有样本，覆盖最短窗口档位。
        // 起点必须严格大于上一条事件时间，否则会破坏单调性（本脚本赖以成立的前提）。
        long tailBase = Math.max(currentMillis, Instant.now().toEpochMilli() - 55_000L) + 1L;
        for (int i = 0; i < 500; i++) {
            events.add(new GeneratedEvent("evt_" + runTag + "_tail_" + i, userIds.get(i % userCount),
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
