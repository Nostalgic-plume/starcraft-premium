package com.shop.rec.flink.sink;

import com.shop.rec.flink.config.FlinkJobConfig;
import com.shop.rec.flink.model.PrefScore;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存库写入器
 *
 * <p>
 * 负责把计算结果写入内存库（对应详细设计 3.2 输出的三类键）：
 * <ul>
 *     <li>用户类目 / 品牌偏好特征 —— 有序集合，成员为维度取值，分值为偏好分</li>
 *     <li>用户特征元信息 —— 哈希，记录最近事件时间与计算时间</li>
 *     <li>商品热度榜 —— 有序集合，成员为商品标识，分值为热度分</li>
 * </ul>
 *
 * <p>
 * <b>连接管理</b>：使用连接池，连接在算子的 {@code open()} 中建立、{@code close()} 中释放，
 * 本类的连接池字段声明为 transient，不参与 Flink 的序列化。
 *
 * @author shop
 */
public class RedisWriter implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户偏好特征键前缀 */
    private static final String KEY_USER_CAT_PREF = "rec:feat:user:%d:cat";

    /** 用户品牌偏好特征键前缀 */
    private static final String KEY_USER_BRAND_PREF = "rec:feat:user:%d:brand";

    /** 用户特征元信息键前缀 */
    private static final String KEY_USER_FEAT_META = "rec:feat:user:%d:meta";

    /** 商品热度榜键 */
    private static final String KEY_HOT_ITEM = "rec:hot:item";

    /** 商品可售状态键前缀 */
    private static final String KEY_ITEM_STATE = "rec:item:state:%d";

    /** 元信息字段：最近事件时间 */
    private static final String META_FIELD_LAST_EVENT_TIME = "lastEventTime";

    /** 元信息字段：最近计算时间 */
    private static final String META_FIELD_CALC_TIME = "calcTime";

    /** 商品状态字段：二级类目标识 */
    private static final String ITEM_STATE_FIELD_CATEGORY = "categoryId";

    /** 商品状态字段：品牌标识 */
    private static final String ITEM_STATE_FIELD_BRAND = "brandId";

    private final String host;

    private final int port;

    private final String password;

    private final int database;

    private transient JedisPool jedisPool;

    /**
     * 构造写入器
     *
     * @param config 作业配置
     */
    public RedisWriter(FlinkJobConfig config) {
        this.host = config.getRedisHost();
        this.port = config.getRedisPort();
        this.password = config.getRedisPassword();
        this.database = config.getRedisDatabase();
    }

    /**
     * 建立连接池（在算子 open 阶段调用）
     */
    public void connect() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        this.jedisPool = new JedisPool(poolConfig, host, port, 2000,
                password == null || password.isEmpty() ? null : password, database);
    }

    /**
     * 释放连接池（在算子 close 阶段调用）
     */
    public void close() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }

    /**
     * 写入用户偏好特征与元信息
     *
     * <p>整体覆盖写偏好特征并续期（对应详细设计 3.2 处理步骤 7 的「覆盖写偏好特征并续期」）。
     *
     * @param userId          用户标识
     * @param categoryPrefs   类目偏好分列表，可为空
     * @param brandPrefs      品牌偏好分列表，可为空
     * @param lastEventTimeMs 本次计算覆盖的最近事件时间（毫秒时间戳），无事件时传 0
     * @param ttlSeconds      特征有效期（秒）
     */
    public void writeUserPreference(long userId, List<PrefScore> categoryPrefs, List<PrefScore> brandPrefs,
                                    long lastEventTimeMs, long ttlSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (categoryPrefs != null && !categoryPrefs.isEmpty()) {
                String catKey = String.format(KEY_USER_CAT_PREF, userId);
                Map<String, Double> catMembers = new HashMap<>(categoryPrefs.size());
                for (PrefScore pref : categoryPrefs) {
                    catMembers.put(String.valueOf(pref.getDimValue()), pref.getScore());
                }
                jedis.zadd(catKey, catMembers);
                jedis.expire(catKey, ttlSeconds);
            }
            if (brandPrefs != null && !brandPrefs.isEmpty()) {
                String brandKey = String.format(KEY_USER_BRAND_PREF, userId);
                Map<String, Double> brandMembers = new HashMap<>(brandPrefs.size());
                for (PrefScore pref : brandPrefs) {
                    brandMembers.put(String.valueOf(pref.getDimValue()), pref.getScore());
                }
                jedis.zadd(brandKey, brandMembers);
                jedis.expire(brandKey, ttlSeconds);
            }

            String metaKey = String.format(KEY_USER_FEAT_META, userId);
            Map<String, String> meta = new HashMap<>(2);
            if (lastEventTimeMs > 0) {
                meta.put(META_FIELD_LAST_EVENT_TIME, String.valueOf(lastEventTimeMs));
            }
            meta.put(META_FIELD_CALC_TIME, String.valueOf(System.currentTimeMillis()));
            jedis.hset(metaKey, meta);
            jedis.expire(metaKey, ttlSeconds);
        }
    }

    /**
     * 写入商品热度榜
     *
     * @param itemHeat   商品标识到热度分的映射
     * @param ttlSeconds 热度榜有效期（秒）
     */
    public void writeItemHeat(Map<Long, Double> itemHeat, long ttlSeconds) {
        if (itemHeat == null || itemHeat.isEmpty()) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Double> members = new HashMap<>(itemHeat.size());
            for (Map.Entry<Long, Double> entry : itemHeat.entrySet()) {
                members.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            jedis.zadd(KEY_HOT_ITEM, members);
            jedis.expire(KEY_HOT_ITEM, ttlSeconds);
        }
    }

    /**
     * 从商品热度榜摘除成员
     *
     * <p>
     * 商品热度是 24 小时滑窗累加值，滑出窗口后应归零。热度归零时若不摘除成员，
     * 该商品会永久滞留在热度榜上被热门召回当作热销商品返回——榜单里出现热度早已归零的商品。
     * 这里不刷新整榜 TTL：摘除只是减少成员，不存在「成员留下但键过期」的不一致。
     *
     * @param itemId 商品标识
     */
    public void removeItemHeat(long itemId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zrem(KEY_HOT_ITEM, String.valueOf(itemId));
        }
    }

    /**
     * 读取商品维度映射
     *
     * <p>数据源为内存库的商品可售状态键；键不存在（商品变更后已失效或尚未刷新）时返回空，
     * 由调用方决定跳过该事件还是沿用上一轮缓存。
     *
     * @param itemId 商品标识
     * @return 商品维度映射；不可用时返回空
     */
    public Optional<ItemDimensionHolder> readItemDimension(long itemId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = String.format(KEY_ITEM_STATE, itemId);
            String categoryId = jedis.hget(key, ITEM_STATE_FIELD_CATEGORY);
            String brandId = jedis.hget(key, ITEM_STATE_FIELD_BRAND);
            if (categoryId == null) {
                return Optional.empty();
            }
            return Optional.of(new ItemDimensionHolder(parseLong(categoryId), parseLong(brandId)));
        }
    }

    /**
     * 商品维度读取结果的轻量载体（避免 sink 包反向依赖 model 包时产生循环）
     */
    public static class ItemDimensionHolder implements Serializable {

        private static final long serialVersionUID = 1L;

        private final long categoryId;

        private final long brandId;

        public ItemDimensionHolder(long categoryId, long brandId) {
            this.categoryId = categoryId;
            this.brandId = brandId;
        }

        public long getCategoryId() {
            return categoryId;
        }

        public long getBrandId() {
            return brandId;
        }
    }

    /**
     * 字符串转长整型，解析失败返回 0
     *
     * @param value 原始值
     * @return 数值
     */
    private long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
