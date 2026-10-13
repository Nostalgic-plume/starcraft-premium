package com.shop.rec.constant;

/**
 * 推荐子系统内存库键定义与有效期常量
 *
 * <p>
 * 严格对应《详细设计说明书》4.2 Redis 键详细定义。键名在实现中直接拼接，
 * 不引入键前缀配置（口径见详细设计 4.2 键设计约定）。
 *
 * <p>
 * <b>分桶状态不在本类中</b>：分钟桶、小时桶、天桶的分桶累加值存放于 Flink 作业的托管状态，
 * 由状态 TTL 与检查点保障，不写入内存库（该调整见详细设计 3.2 与 4.2）。
 *
 * @author shop
 */
public final class RecRedisKeys {

    private RecRedisKeys() {
    }

    // ==================== 键前缀 ====================

    /** 全局键前缀 */
    public static final String PREFIX = "rec:";

    // ==================== 1. 用户偏好特征 ====================

    /** 用户类目偏好特征（有序集合，8 天） */
    private static final String USER_CAT_PREF_TEMPLATE = PREFIX + "feat:user:%d:cat";

    /** 用户品牌偏好特征（有序集合，8 天） */
    private static final String USER_BRAND_PREF_TEMPLATE = PREFIX + "feat:user:%d:brand";

    /** 用户特征元信息（哈希，8 天） */
    private static final String USER_FEAT_META_TEMPLATE = PREFIX + "feat:user:%d:meta";

    /**
     * 用户类目偏好特征键
     *
     * @param userId 用户标识
     * @return 键名 rec:feat:user:{userId}:cat
     */
    public static String userCatPref(Long userId) {
        return String.format(USER_CAT_PREF_TEMPLATE, userId);
    }

    /**
     * 用户品牌偏好特征键
     *
     * @param userId 用户标识
     * @return 键名 rec:feat:user:{userId}:brand
     */
    public static String userBrandPref(Long userId) {
        return String.format(USER_BRAND_PREF_TEMPLATE, userId);
    }

    /**
     * 用户特征元信息键
     *
     * @param userId 用户标识
     * @return 键名 rec:feat:user:{userId}:meta
     */
    public static String userFeatMeta(Long userId) {
        return String.format(USER_FEAT_META_TEMPLATE, userId);
    }

    // ==================== 2. 商品热度与索引 ====================

    /** 商品热度榜（有序集合，1 天） */
    public static final String HOT_ITEM = PREFIX + "hot:item";

    /** 类目候选索引（有序集合，1 天）：各二级类目下的候选商品及类目内热度分 */
    private static final String CAT_ITEMS_TEMPLATE = PREFIX + "cat:items:%d";

    /** 商品可售状态（哈希，1 天，变更时主动删除） */
    private static final String ITEM_STATE_TEMPLATE = PREFIX + "item:state:%d";

    /**
     * 类目候选索引键
     *
     * @param categoryId 二级类目标识
     * @return 键名 rec:cat:items:{categoryId}
     */
    public static String catItems(Long categoryId) {
        return String.format(CAT_ITEMS_TEMPLATE, categoryId);
    }

    /**
     * 商品可售状态键
     *
     * @param itemId 商品标识
     * @return 键名 rec:item:state:{itemId}
     */
    public static String itemState(Long itemId) {
        return String.format(ITEM_STATE_TEMPLATE, itemId);
    }

    /**
     * 商品可售状态键的匹配模式
     *
     * <p>供「全量重建前清空旧状态缓存」使用（SCAN 匹配）。必须按模式清理的原因：
     * 重建的分页遍历只会返回<b>当前仍存在</b>的商品，已被物理删除的商品不在其中，
     * 其状态键不会被写入语句覆盖，也不会被对账任务清掉（对账只遍历可售商品），
     * 最终导致推荐结果里出现「已不存在但仍在售」的悬空商品。
     *
     * @return 键模式 rec:item:state:*
     */
    public static String itemStatePattern() {
        return PREFIX + "item:state:*";
    }

    // ==================== 3. 过滤与排除清单 ====================

    /** 过滤清单（集合，随规则同步）：命中该规则的商品标识 */
    private static final String FILTER_TEMPLATE = PREFIX + "filter:%s";

    /** 用户已购与已加购排除清单（集合，7 天） */
    private static final String EXCLUDE_TEMPLATE = PREFIX + "exclude:user:%d";

    /**
     * 过滤清单键
     *
     * @param ruleType 规则类型（blacklist / member_only / region_limit / low_quality）
     * @return 键名 rec:filter:{ruleType}
     */
    public static String filter(String ruleType) {
        return String.format(FILTER_TEMPLATE, ruleType);
    }

    /**
     * 用户排除清单键
     *
     * @param userId 用户标识
     * @return 键名 rec:exclude:user:{userId}
     */
    public static String exclude(Long userId) {
        return String.format(EXCLUDE_TEMPLATE, userId);
    }

    // ==================== 4. 榜单快照 ====================

    /** 榜单快照（字符串 JSON，300 秒）：供同一用户翻页复用 */
    private static final String RESULT_TEMPLATE = PREFIX + "result:user:%d";

    /**
     * 榜单快照键
     *
     * @param userId 用户标识
     * @return 键名 rec:result:user:{userId}
     */
    public static String result(Long userId) {
        return String.format(RESULT_TEMPLATE, userId);
    }

    // ==================== 5. 上报防护 ====================

    /** 上报去重标识（字符串，3600 秒） */
    private static final String DEDUP_TEMPLATE = PREFIX + "dedup:event:%s";

    /** 上报限频计数（字符串计数，60 秒） */
    private static final String LIMIT_REPORT_TEMPLATE = PREFIX + "limit:report:%d";

    /**
     * 上报去重标识键
     *
     * @param eventId 事件幂等标识
     * @return 键名 rec:dedup:event:{eventId}
     */
    public static String dedup(String eventId) {
        return String.format(DEDUP_TEMPLATE, eventId);
    }

    /**
     * 上报限频计数键
     *
     * @param userId 用户标识
     * @return 键名 rec:limit:report:{userId}
     */
    public static String limitReport(Long userId) {
        return String.format(LIMIT_REPORT_TEMPLATE, userId);
    }

    // ==================== 6. 配置副本 ====================

    /** 配置热更新副本（字符串，长期，主动更新） */
    private static final String CFG_TEMPLATE = PREFIX + "cfg:%s";

    /** 配置版本号键：各模块每 30 秒比对一次，发现变化后重新加载 */
    public static final String CFG_VERSION = PREFIX + "cfg:version";

    /**
     * 配置副本键
     *
     * @param configKey 配置键
     * @return 键名 rec:cfg:{configKey}
     */
    public static String cfg(String configKey) {
        return String.format(CFG_TEMPLATE, configKey);
    }

    // ==================== 7. 特征元信息字段名 ====================

    /** 元信息字段：最近事件时间 */
    public static final String META_FIELD_LAST_EVENT_TIME = "lastEventTime";

    /** 元信息字段：最近计算时间 */
    public static final String META_FIELD_CALC_TIME = "calcTime";

    /** 元信息字段：窗口档位标记 */
    public static final String META_FIELD_WINDOW_FLAG = "windowFlag";

    // ==================== 8. 有效期（秒） ====================

    /** 偏好特征有效期：8 天 */
    public static final long TTL_USER_FEAT = 8 * 24 * 3600L;

    /** 商品热度榜有效期：1 天 */
    public static final long TTL_HOT_ITEM = 24 * 3600L;

    /** 类目候选索引有效期：1 天 */
    public static final long TTL_CAT_ITEMS = 24 * 3600L;

    /** 商品可售状态有效期：1 天（商品变更时主动删除，不等待过期） */
    public static final long TTL_ITEM_STATE = 24 * 3600L;

    /** 榜单快照有效期：300 秒（默认值，可由配置项 cache.result.ttl 覆盖） */
    public static final long TTL_RESULT = 300L;

    /** 上报去重标识有效期：3600 秒 */
    public static final long TTL_DEDUP = 3600L;

    /** 上报限频计数有效期：60 秒 */
    public static final long TTL_LIMIT_REPORT = 60L;

    /** 已购已加购排除清单有效期：7 天（默认值，可由配置项 exclude.recent.days 覆盖） */
    public static final long TTL_EXCLUDE = 7 * 24 * 3600L;

    // ==================== 9. 采集防护运行时状态（实现补充） ====================
    // 说明：详细设计 4.2 的键表聚焦业务数据与限流键；以下为 M-01 采集防护所需的运行时状态，
    // 均带短有效期自动清理，不对外提供查询能力，属实现细节。

    /** 单用户单商品单行为类型的分钟级上报计数（第二级限频） */
    private static final String LIMIT_ITEM_TEMPLATE = PREFIX + "limit:item:%d:%d:%s";

    /** 用户最近一次浏览某商品的时间标记（无效行为识别用） */
    private static final String RECENT_VIEW_TEMPLATE = PREFIX + "recent:view:%d:%d";

    /** 来源对单个商品的分钟级浏览计数（爬虫判定用） */
    private static final String CRAWLER_CNT_TEMPLATE = PREFIX + "crawler:cnt:%s:%d";

    /** 来源维度统计哈希（爬虫判定前置校验用）：total 浏览总数 / withDuration 已采集停留时长数 */
    private static final String CRAWLER_STAT_TEMPLATE = PREFIX + "crawler:stat:%s";

    /** 来源封禁标记：命中爬虫后该来源浏览事件在封禁期内全部丢弃 */
    private static final String CRAWLER_BAN_TEMPLATE = PREFIX + "crawler:ban:%s";

    /**
     * 单用户单商品单行为类型上报计数键
     *
     * @param userId    用户标识
     * @param itemId    商品标识
     * @param eventType 行为类型
     * @return 键名
     */
    public static String limitItem(Long userId, Long itemId, String eventType) {
        return String.format(LIMIT_ITEM_TEMPLATE, userId, itemId, eventType);
    }

    /**
     * 用户最近浏览标记键
     *
     * @param userId 用户标识
     * @param itemId 商品标识
     * @return 键名
     */
    public static String recentView(Long userId, Long itemId) {
        return String.format(RECENT_VIEW_TEMPLATE, userId, itemId);
    }

    /**
     * 来源对单个商品的浏览计数键
     *
     * @param source 来源标识
     * @param itemId 商品标识
     * @return 键名
     */
    public static String crawlerCount(String source, Long itemId) {
        return String.format(CRAWLER_CNT_TEMPLATE, source, itemId);
    }

    /**
     * 来源统计哈希键
     *
     * @param source 来源标识
     * @return 键名
     */
    public static String crawlerStat(String source) {
        return String.format(CRAWLER_STAT_TEMPLATE, source);
    }

    /**
     * 来源封禁标记键
     *
     * @param source 来源标识
     * @return 键名
     */
    public static String crawlerBan(String source) {
        return String.format(CRAWLER_BAN_TEMPLATE, source);
    }

    /** 最近浏览标记有效期：5 秒（无效行为识别的时间窗口） */
    public static final long TTL_RECENT_VIEW = 5L;

    /** 爬虫计数与来源统计有效期：60 秒（判定窗口） */
    public static final long TTL_CRAWLER_WINDOW = 60L;

    /** 爬虫封禁有效期：5 分钟（命中后丢弃该来源浏览事件的时长） */
    public static final long TTL_CRAWLER_BAN = 5 * 60L;

    /** 来源统计哈希字段：浏览总数 */
    public static final String CRAWLER_FIELD_TOTAL = "total";

    /** 来源统计哈希字段：已采集停留时长的浏览数 */
    public static final String CRAWLER_FIELD_WITH_DURATION = "withDuration";

    // ==================== 10. 商品可售状态字段名 ====================
    // 对应详细设计 4.2 键 6「商品可售状态（rec:item:state:{itemId}）」的哈希字段，由 M-04 写入。

    /** 商品状态字段：上下架状态 */
    public static final String ITEM_STATE_FIELD_STATUS = "status";

    /** 商品状态字段：库存 */
    public static final String ITEM_STATE_FIELD_STOCK = "stock";

    /** 商品状态字段：二级类目标识 */
    public static final String ITEM_STATE_FIELD_CATEGORY = "categoryId";

    /** 商品状态字段：品牌标识 */
    public static final String ITEM_STATE_FIELD_BRAND = "brandId";

    /** 商品状态字段：店铺标识 */
    public static final String ITEM_STATE_FIELD_SHOP = "shopId";

    /** 商品上架状态取值 */
    public static final String ITEM_STATUS_ON_SALE = "1";
}
