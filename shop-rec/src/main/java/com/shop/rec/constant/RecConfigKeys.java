package com.shop.rec.constant;

import java.util.Map;

/**
 * 推荐策略配置键与内置默认值
 *
 * <p>
 * 键名与默认值严格对应《详细设计说明书》9.2 配置参数示例。
 * 默认值用于内存库配置副本不可读时的兜底——任何模块在配置读取失败时都必须
 * 回退到本类定义的默认值继续服务，禁止因配置不可读而中断链路。
 *
 * @author shop
 */
public final class RecConfigKeys {

    private RecConfigKeys() {
    }

    // ==================== 行为权值 ====================

    /** 浏览行为权值（弱兴趣信号，量最大） */
    public static final String WEIGHT_BEHAVIOR_VIEW = "weight.behavior.view";

    /** 加购行为权值（中等兴趣信号） */
    public static final String WEIGHT_BEHAVIOR_ADD_CART = "weight.behavior.add_cart";

    /** 收藏行为权值（强兴趣信号） */
    public static final String WEIGHT_BEHAVIOR_FAVORITE = "weight.behavior.favorite";

    /** 购买行为权值（最强兴趣信号） */
    public static final String WEIGHT_BEHAVIOR_PURCHASE = "weight.behavior.purchase";

    // ==================== 窗口权值（六项之和必须为 1） ====================

    /** 1 分钟窗口权值 */
    public static final String WEIGHT_WINDOW_1MIN = "weight.window.1min";

    /** 5 分钟窗口权值 */
    public static final String WEIGHT_WINDOW_5MIN = "weight.window.5min";

    /** 10 分钟窗口权值 */
    public static final String WEIGHT_WINDOW_10MIN = "weight.window.10min";

    /** 1 小时窗口权值 */
    public static final String WEIGHT_WINDOW_1H = "weight.window.1h";

    /** 24 小时窗口权值 */
    public static final String WEIGHT_WINDOW_24H = "weight.window.24h";

    /** 7 天窗口权值 */
    public static final String WEIGHT_WINDOW_7D = "weight.window.7d";

    // ==================== 特征计算 ====================

    /** 单维度偏好分封顶阈值 */
    public static final String FEATURE_CAP_THRESHOLD = "feature.cap.threshold";

    // ==================== 召回 ====================

    /** 两路召回占比，格式「类目:热门」，两数之和必须为 100 */
    public static final String RECALL_RATIO = "recall.ratio";

    /** 候选池目标规模 */
    public static final String RECALL_POOL_SIZE = "recall.pool.size";

    /** 类目偏好召回取用的偏好类目数 */
    public static final String RECALL_CATEGORY_TOPN = "recall.category.topN";

    /** 单类目候选条数 */
    public static final String RECALL_CATEGORY_LIMIT = "recall.category.limit";

    /** 热门召回候选条数 */
    public static final String RECALL_HOT_LIMIT = "recall.hot.limit";

    // ==================== 打分（三项之和必须为 1） ====================

    /** 类目偏好分权值 */
    public static final String RANK_WEIGHT_CAT = "rank.weight.cat";

    /** 商品热度分权值 */
    public static final String RANK_WEIGHT_HOT = "rank.weight.hot";

    /** 品牌偏好分权值（默认 0，预留） */
    public static final String RANK_WEIGHT_BRAND = "rank.weight.brand";

    /**
     * 榜单首位个性化配额（条）
     *
     * <p>榜单前 N 位改用类目偏好路的候选（纯个性化），其后位次走原有融合排序。
     * <b>置 0 即关闭</b>，线上出问题时可不开版止血。
     *
     * <p>注意该步骤会**有意打破「总分降序」不变量**——前 N 名的融合总分可能低于第 N+1 名，
     * 这是需求要求的效果，详见 {@code RankService#applyHeadQuota} 的方法注释。
     */
    public static final String RANK_HEAD_PERSONALIZED_SIZE = "rank.head.personalized.size";

    // ==================== 缓存 ====================

    /** 榜单快照有效期（秒） */
    public static final String CACHE_RESULT_TTL = "cache.result.ttl";

    // ==================== 采集与限频 ====================

    /** 事件采样率，取值 0.1~1.0 */
    public static final String COLLECT_SAMPLE_RATE = "collect.sample.rate";

    /** 单用户每分钟上报上限 */
    public static final String LIMIT_REPORT_PER_MINUTE = "limit.report.perMinute";

    /**
     * 单用户单商品单行为类型每分钟上报上限
     * 对应详细设计 3.1 步骤 5 的第二条限频规则，为使该阈值可配置而纳入本表
     */
    public static final String LIMIT_REPORT_ITEM_PER_MINUTE = "limit.report.itemPerMinute";

    // ==================== 过滤 ====================

    /** 已购与已加购商品的排除天数，取 0 表示不启用该过滤规则 */
    public static final String EXCLUDE_RECENT_DAYS = "exclude.recent.days";

    // ==================== 开关 ====================

    /** 强制降级状态（高危项：仅指定运营账号可改，默认 30 分钟后自动复位） */
    public static final String DEGRADE_FORCE = "degrade.force";

    // ==================== 内置默认值 ====================

    /**
     * 配置键到内置默认值的映射
     *
     * <p>内存库配置副本不可读时，各模块读取配置将回退到本映射；
     * 映射中不存在的键返回 null，由调用方决定兜底策略。
     */
    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(WEIGHT_BEHAVIOR_VIEW, "1"),
            Map.entry(WEIGHT_BEHAVIOR_ADD_CART, "3"),
            Map.entry(WEIGHT_BEHAVIOR_FAVORITE, "4"),
            Map.entry(WEIGHT_BEHAVIOR_PURCHASE, "8"),

            Map.entry(WEIGHT_WINDOW_1MIN, "0.30"),
            Map.entry(WEIGHT_WINDOW_5MIN, "0.20"),
            Map.entry(WEIGHT_WINDOW_10MIN, "0.15"),
            Map.entry(WEIGHT_WINDOW_1H, "0.15"),
            Map.entry(WEIGHT_WINDOW_24H, "0.15"),
            Map.entry(WEIGHT_WINDOW_7D, "0.05"),

            Map.entry(FEATURE_CAP_THRESHOLD, "100"),

            Map.entry(RECALL_RATIO, "70:30"),
            Map.entry(RECALL_POOL_SIZE, "200"),
            Map.entry(RECALL_CATEGORY_TOPN, "3"),
            Map.entry(RECALL_CATEGORY_LIMIT, "50"),
            Map.entry(RECALL_HOT_LIMIT, "60"),

            Map.entry(RANK_WEIGHT_CAT, "0.6"),
            Map.entry(RANK_WEIGHT_HOT, "0.4"),
            Map.entry(RANK_WEIGHT_BRAND, "0"),
            Map.entry(RANK_HEAD_PERSONALIZED_SIZE, "5"),

            Map.entry(CACHE_RESULT_TTL, "300"),

            Map.entry(COLLECT_SAMPLE_RATE, "1.0"),
            Map.entry(LIMIT_REPORT_PER_MINUTE, "60"),
            Map.entry(LIMIT_REPORT_ITEM_PER_MINUTE, "10"),

            Map.entry(EXCLUDE_RECENT_DAYS, "7"),

            Map.entry(DEGRADE_FORCE, "正常")
    );
}
