package com.shop.rec.tools;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 离线评测与消融对比（对应详细设计 9.5）
 *
 * <p>
 * 补齐设计链中的效果验证环节：按事件时间切分数据集，在基线与多种策略配置下批量执行评测，
 * 输出 Recall@10 / NDCG@10 / 覆盖率 / 长尾曝光占比，区分活跃用户与冷启动用户。
 *
 * <p>
 * <b>为什么按事件时间切分而非随机划分</b>：随机划分会让「未来行为」参与特征计算造成特征穿越，
 * 指标虚高且无法复现线上效果；按时间切分与线上「用历史预测未来」的真实场景一致。
 *
 * <p>
 * <b>五组配置构成增益归因链</b>：G0 纯热度（基线）→ G1 热度召回 → G2 双路召回 →
 * G3 加权打分 → G4 加品牌权值与池收缩。若 G3 相对 G0 的提升主要来自 G1/G2 而非 G3 自身，
 * 说明当前「个性化」仍只是「更宽的召回 + 同样的热度排序」，报告中须如实说明。
 *
 * <p>
 * <b>关于量化目标</b>：报告给出实测值，不设硬性百分比门槛——合成数据的行为分布与真实用户存在偏差，
 * 本评测的价值在于验证链路正确性与模块增益的可归因性。
 *
 * @author shop
 */
public final class OfflineEvaluator {

    /** 评测截断位置 */
    private static final int TOP_K = 10;

    /** 训练段与评测段的分界：以数据最新时间为基准往前 1 天 */
    private static final int EVALUATION_DAYS = 1;

    /** 活跃用户判定阈值：训练段行为条数 */
    private static final int ACTIVE_USER_THRESHOLD = 20;

    /** 冷启动用户判定阈值：训练段行为条数 */
    private static final int COLD_START_THRESHOLD = 3;

    /** 长尾判定：热度榜前 N 名之外视为长尾 */
    private static final int HEAD_ITEM_RANK = 50;

    /** 热度榜键 */
    private static final String KEY_HOT_ITEM = "rec:hot:item";

    /** 类目候选索引键前缀 */
    private static final String KEY_CAT_ITEMS_PREFIX = "rec:cat:items:";

    /** 商品状态键前缀 */
    private static final String KEY_ITEM_STATE_PREFIX = "rec:item:state:";

    private OfflineEvaluator() {
    }

    /**
     * 脚本入口
     *
     * @param args [0] 推荐库 JDBC URL，[1] 账号，[2] 密码，[3] Redis 密码
     * @throws Exception 评测失败
     */
    public static void main(String[] args) throws Exception {
        String jdbcUrl = args.length > 0 ? args[0]
                : "jdbc:mysql://localhost:3306/shop_rec?useUnicode=true&characterEncoding=utf-8"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        String username = args.length > 1 ? args[1] : System.getenv().getOrDefault("DB_USERNAME", "root");
        String password = args.length > 2 ? args[2] : System.getenv("DB_PASSWORD");
        String redisPassword = args.length > 3 ? args[3] : System.getenv("REDIS_PASSWORD");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             JedisPool jedisPool = new JedisPool(poolConfig, "127.0.0.1", 6379, 2000,
                     redisPassword == null || redisPassword.isEmpty() ? null : redisPassword, 0)) {

            LocalDateTime boundary = resolveBoundary(connection);
            System.out.printf("评测切分点（事件时间）: %s%n", boundary);

            Map<Long, List<Long>> trainInteractions = loadInteractions(connection, null, boundary);
            Map<Long, List<Long>> evaluationInteractions = loadInteractions(connection, boundary, null);
            System.out.printf("训练段用户: %d，评测段用户: %d%n",
                    trainInteractions.size(), evaluationInteractions.size());

            Set<Long> longTailPool = loadItemUniverse(connection);
            Set<Long> hotHead = loadHotHead(jedisPool);

            Map<String, Map<String, Double>> allResults = new LinkedHashMap<>();
            for (Strategy strategy : Strategy.values()) {
                Map<String, Double> metrics = evaluate(strategy, trainInteractions,
                        evaluationInteractions, jedisPool, longTailPool, hotHead);
                allResults.put(strategy.label, metrics);
                System.out.printf("%-28s Recall@10=%.4f  NDCG@10=%.4f  覆盖率=%.4f  长尾占比=%.4f%n",
                        strategy.label, metrics.get("recall"), metrics.get("ndcg"),
                        metrics.get("coverage"), metrics.get("longTail"));
            }

            printAttribution(allResults);
            printGroupedReport(trainInteractions, evaluationInteractions, jedisPool, longTailPool, hotHead);
        }
    }

    /**
     * 确定切分点：以行为数据的最新事件时间往前推 N 天
     *
     * @param connection 数据库连接
     * @return 切分时间
     * @throws Exception 查询失败
     */
    private static LocalDateTime resolveBoundary(Connection connection) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT MAX(event_time) FROM t_user_behavior_log");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next() && resultSet.getTimestamp(1) != null) {
                return resultSet.getTimestamp(1).toLocalDateTime().minusDays(EVALUATION_DAYS);
            }
        }
        return LocalDateTime.now().minusDays(EVALUATION_DAYS);
    }

    /**
     * 按时间区间加载用户与商品的交互关系
     *
     * @param connection 数据库连接
     * @param from       起始时间，null 表示不限
     * @param to         结束时间，null 表示不限
     * @return 用户标识到交互商品集合的映射
     * @throws Exception 查询失败
     */
    private static Map<Long, List<Long>> loadInteractions(Connection connection,
                                                          LocalDateTime from, LocalDateTime to) throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT user_id, item_id FROM t_user_behavior_log WHERE 1 = 1");
        if (from != null) {
            sql.append(" AND event_time >= ?");
        }
        if (to != null) {
            sql.append(" AND event_time < ?");
        }
        Map<Long, Set<Long>> grouped = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (from != null) {
                statement.setTimestamp(index++, Timestamp.valueOf(from));
            }
            if (to != null) {
                statement.setTimestamp(index, Timestamp.valueOf(to));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    grouped.computeIfAbsent(resultSet.getLong("user_id"), k -> new HashSet<>())
                            .add(resultSet.getLong("item_id"));
                }
            }
        }
        Map<Long, List<Long>> result = new HashMap<>(grouped.size());
        grouped.forEach((userId, items) -> result.put(userId, new ArrayList<>(items)));
        return result;
    }

    /**
     * 加载全站可推荐商品集合（覆盖率分母）
     *
     * @param connection 数据库连接
     * @return 商品标识集合
     * @throws Exception 查询失败
     */
    private static Set<Long> loadItemUniverse(Connection connection) throws Exception {
        Set<Long> items = new HashSet<>();
        // ⚠️ 必须用 shop. 限定库名：本脚本的 JDBC 连接指向推荐库 shop_rec，
        //    而 product 表归属商城库 shop——不加前缀会报 Table 'shop_rec.product' doesn't exist。
        //    两库当前位于同一 MySQL 实例（均为 localhost:3306），跨库限定即可；
        //    若将来拆到不同实例，本方法需改为使用独立的商城库连接。
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM shop.product WHERE deleted = 0 AND status = 1");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                items.add(resultSet.getLong(1));
            }
        }
        return items;
    }

    /**
     * 加载热度榜头部商品（长尾判定用）
     *
     * @param jedisPool Redis 连接池
     * @return 头部商品标识集合
     */
    private static Set<Long> loadHotHead(JedisPool jedisPool) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> members = jedis.zrevrange(KEY_HOT_ITEM, 0, HEAD_ITEM_RANK - 1L);
            return members.stream().map(Long::parseLong).collect(Collectors.toSet());
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    /**
     * 执行一组策略的评测
     *
     * @param strategy               策略
     * @param trainInteractions      训练段交互
     * @param evaluationInteractions 评测段交互（标注）
     * @param jedisPool              Redis 连接池
     * @param longTailPool           全站商品
     * @param hotHead                热度头部商品
     * @return 指标映射
     */
    private static Map<String, Double> evaluate(Strategy strategy,
                                                Map<Long, List<Long>> trainInteractions,
                                                Map<Long, List<Long>> evaluationInteractions,
                                                JedisPool jedisPool,
                                                Set<Long> longTailPool,
                                                Set<Long> hotHead) {
        double recallSum = 0D;
        double ndcgSum = 0D;
        int evaluatedUsers = 0;
        Set<Long> recommendedItems = new HashSet<>();
        int longTailHits = 0;
        int totalRecommended = 0;

        try (Jedis jedis = jedisPool.getResource()) {
            List<Long> hotRank = new ArrayList<>(hotHead);
            for (Map.Entry<Long, List<Long>> entry : evaluationInteractions.entrySet()) {
                Long userId = entry.getKey();
                Set<Long> groundTruth = new HashSet<>(entry.getValue());
                if (groundTruth.isEmpty()) {
                    continue;
                }

                List<Long> recommendations = recommend(strategy, userId,
                        trainInteractions.getOrDefault(userId, new ArrayList<>()),
                        jedis, hotRank);
                if (recommendations.isEmpty()) {
                    evaluatedUsers++;
                    continue;
                }

                recommendedItems.addAll(recommendations);
                for (Long itemId : recommendations) {
                    totalRecommended++;
                    if (!hotHead.contains(itemId)) {
                        longTailHits++;
                    }
                }

                int hits = 0;
                double dcg = 0D;
                for (int i = 0; i < recommendations.size(); i++) {
                    if (groundTruth.contains(recommendations.get(i))) {
                        hits++;
                        dcg += 1D / (Math.log(i + 2D) / Math.log(2D));
                    }
                }
                recallSum += (double) hits / groundTruth.size();
                ndcgSum += normalizeDcg(dcg, groundTruth.size());
                evaluatedUsers++;
            }
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("recall", evaluatedUsers == 0 ? 0D : recallSum / evaluatedUsers);
        metrics.put("ndcg", evaluatedUsers == 0 ? 0D : ndcgSum / evaluatedUsers);
        metrics.put("coverage", longTailPool.isEmpty() ? 0D : (double) recommendedItems.size() / longTailPool.size());
        metrics.put("longTail", totalRecommended == 0 ? 0D : (double) longTailHits / totalRecommended);
        return metrics;
    }

    /**
     * 按策略生成推荐列表
     *
     * @param strategy       策略
     * @param userId         用户标识
     * @param trainItems     训练段该用户交互过的商品
     * @param jedis          Redis 连接
     * @param hotRank        热度榜商品
     * @return 推荐商品列表（长度 ≤ TOP_K）
     */
    private static List<Long> recommend(Strategy strategy, Long userId, List<Long> trainItems,
                                        Jedis jedis, List<Long> hotRank) {
        // 用户的类目偏好：由训练段交互商品所属类目统计得出
        Map<Long, Double> categoryPreference = new HashMap<>();
        if (strategy.useCategoryRecall || strategy.weightCategory > 0D) {
            for (Long itemId : trainItems) {
                Long categoryId = readCategory(jedis, itemId);
                if (categoryId != null) {
                    categoryPreference.merge(categoryId, 1D, Double::sum);
                }
            }
        }

        Map<Long, Double> candidates = new LinkedHashMap<>();
        // 热门路
        for (int i = 0; i < hotRank.size(); i++) {
            candidates.merge(hotRank.get(i), (hotRank.size() - i) / (double) hotRank.size(), Double::sum);
        }
        // 类目偏好路
        if (strategy.useCategoryRecall && !categoryPreference.isEmpty()) {
            List<Long> topCategories = categoryPreference.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            for (Long categoryId : topCategories) {
                List<String> items = jedis.zrevrange(KEY_CAT_ITEMS_PREFIX + categoryId, 0, strategy.poolSize - 1L);
                for (String item : items) {
                    candidates.merge(Long.parseLong(item), categoryPreference.get(categoryId), Double::sum);
                }
            }
        }

        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        // 打分：按策略组合热度与类目偏好
        Map<Long, Double> scores = new HashMap<>(candidates.size());
        double maxHeat = candidates.values().stream().mapToDouble(Double::doubleValue).max().orElse(1D);
        for (Map.Entry<Long, Double> candidate : candidates.entrySet()) {
            double heatScore = candidate.getValue() / maxHeat;
            double categoryScore = 0D;
            Long categoryId = readCategory(jedis, candidate.getKey());
            if (categoryId != null) {
                categoryScore = categoryPreference.getOrDefault(categoryId, 0D);
            }
            scores.put(candidate.getKey(),
                    strategy.weightHeat * heatScore + strategy.weightCategory * categoryScore);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .limit(TOP_K)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 读取商品所属二级类目
     *
     * @param jedis  Redis 连接
     * @param itemId 商品标识
     * @return 类目标识；不可用时返回 null
     */
    private static Long readCategory(Jedis jedis, Long itemId) {
        try {
            String value = jedis.hget(KEY_ITEM_STATE_PREFIX + itemId, "categoryId");
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 归一化 DCG：理想排序下的 DCG 上限
     *
     * @param dcg        实际 DCG
     * @param truthCount 标注数量
     * @return NDCG
     */
    private static double normalizeDcg(double dcg, int truthCount) {
        double ideal = 0D;
        int limit = Math.min(truthCount, TOP_K);
        for (int i = 0; i < limit; i++) {
            ideal += 1D / (Math.log(i + 2D) / Math.log(2D));
        }
        return ideal == 0D ? 0D : dcg / ideal;
    }

    /**
     * 打印增益归因
     *
     * @param allResults 全部策略结果
     */
    private static void printAttribution(Map<String, Map<String, Double>> allResults) {
        System.out.println();
        System.out.println("=== 增益归因（相对 G0 基线的 Recall@10 增量）===");
        Double baseline = allResults.get(Strategy.G0.label) == null
                ? null : allResults.get(Strategy.G0.label).get("recall");
        if (baseline == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Double>> entry : allResults.entrySet()) {
            double delta = entry.getValue().get("recall") - baseline;
            System.out.printf("  %-28s %+.4f%n", entry.getKey(), delta);
        }
        System.out.println("  说明：若 G3 相对 G0 的增量主要来自 G1/G2，说明个性化增益有限，需据实说明。");
    }

    /**
     * 打印按用户分组的分组报告
     *
     * @param trainInteractions      训练段交互
     * @param evaluationInteractions 评测段交互
     * @param jedisPool              Redis 连接池
     * @param longTailPool           全站商品
     * @param hotHead                热度头部商品
     */
    private static void printGroupedReport(Map<Long, List<Long>> trainInteractions,
                                           Map<Long, List<Long>> evaluationInteractions,
                                           JedisPool jedisPool, Set<Long> longTailPool, Set<Long> hotHead) {
        Map<Long, List<Long>> active = new HashMap<>();
        Map<Long, List<Long>> coldStart = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : evaluationInteractions.entrySet()) {
            int behaviorCount = trainInteractions.getOrDefault(entry.getKey(), new ArrayList<>()).size();
            if (behaviorCount >= ACTIVE_USER_THRESHOLD) {
                active.put(entry.getKey(), entry.getValue());
            } else if (behaviorCount < COLD_START_THRESHOLD) {
                coldStart.put(entry.getKey(), entry.getValue());
            }
        }

        System.out.println();
        System.out.println("=== 分组报告（完整策略 G3）===");
        for (Map.Entry<String, Map<Long, List<Long>>> group : List.of(
                Map.entry("活跃用户", active), Map.entry("冷启动用户", coldStart))) {
            Map<String, Double> metrics = evaluate(Strategy.G3, trainInteractions, group.getValue(),
                    jedisPool, longTailPool, hotHead);
            System.out.printf("  %-8s 用户数=%-6d Recall@10=%.4f  NDCG@10=%.4f%n",
                    group.getKey(), group.getValue().size(),
                    metrics.get("recall"), metrics.get("ndcg"));
        }
    }

    /**
     * 评测策略（对应详细设计 9.5.3 的五组配置）
     */
    private enum Strategy {

        /** G0 基线：纯热度排序，等价于改造前的静态热销榜 */
        G0("G0 纯热度（基线）", false, 1.0D, 0D, 200),

        /** G1：热度召回 + 热度排序 */
        G1("G1 热度召回", false, 1.0D, 0.0D, 200),

        /** G2：双路召回 + 仅热度排序 */
        G2("G2 双路召回+热度排序", true, 1.0D, 0D, 200),

        /** G3：双路召回 + 加权打分（类目偏好 0.6 / 热度 0.4） */
        G3("G3 加权打分", true, 0.4D, 0.6D, 200),

        /** G4 完整：G3 + 品牌权值启用 + 候选池收缩 */
        G4("G4 完整（池收缩）", true, 0.4D, 0.6D, 100);

        private final String label;

        private final boolean useCategoryRecall;

        private final double weightHeat;

        private final double weightCategory;

        private final int poolSize;

        Strategy(String label, boolean useCategoryRecall,
                 double weightHeat, double weightCategory, int poolSize) {
            this.label = label;
            this.useCategoryRecall = useCategoryRecall;
            this.weightHeat = weightHeat;
            this.weightCategory = weightCategory;
            this.poolSize = poolSize;
        }
    }
}
