package com.shop.rec.common;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 推荐子系统内存库访问组件
 *
 * <p>
 * 统一封装推荐侧对内存库的读写操作，供 M-01~M-10 各模块使用。键名一律从
 * {@link com.shop.rec.constant.RecRedisKeys} 构造，不在本类中拼接业务语义。
 *
 * <p>
 * <b>异常处理约定</b>：本组件不吞异常，内存库不可用时直接抛出，由调用方按所在环节的
 * 降级语义处理（如特征读取失败按无特征处理、快照读写失败跳过缓存直接计算）。
 *
 * @author shop
 */
@Component
@RequiredArgsConstructor
public class RecRedis {

    private final StringRedisTemplate redisTemplate;

    // ==================== 有序集合（偏好特征、热度榜、类目索引） ====================

    /**
     * 写入单个有序集合成员
     *
     * @param key    键名
     * @param member 成员
     * @param score  分值
     */
    public void zadd(String key, String member, double score) {
        redisTemplate.opsForZSet().add(key, member, score);
    }

    /**
     * 批量写入有序集合成员
     *
     * @param key           键名
     * @param scoredMembers 成员与分值的映射
     */
    public void zaddAll(String key, Map<String, Double> scoredMembers) {
        if (scoredMembers == null || scoredMembers.isEmpty()) {
            return;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = scoredMembers.entrySet().stream()
                .map(e -> ZSetOperations.TypedTuple.of(e.getKey(), e.getValue()))
                .collect(java.util.stream.Collectors.toSet());
        redisTemplate.opsForZSet().add(key, tuples);
    }

    /**
     * 按分值降序取指定区间成员（含分值）
     *
     * <p>业务语义：取偏好分 TopN 类目、取热度榜前 N 名商品。
     *
     * @param key   键名
     * @param start 起始下标，从 0 开始
     * @param end   结束下标，-1 表示末尾
     * @return 成员与分值的降序列表；键不存在时返回空列表
     */
    public List<ZSetOperations.TypedTuple<String>> zrevrangeWithScores(String key, long start, long end) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        return tuples == null ? Collections.emptyList() : List.copyOf(tuples);
    }

    /**
     * 按分值降序取指定区间成员
     *
     * @param key   键名
     * @param start 起始下标，从 0 开始
     * @param end   结束下标，-1 表示末尾
     * @return 成员集合的降序列表；键不存在时返回空集合
     */
    public Set<String> zrevrange(String key, long start, long end) {
        Set<String> members = redisTemplate.opsForZSet().reverseRange(key, start, end);
        return members == null ? Collections.emptySet() : members;
    }

    /**
     * 查询成员在有序集合中的分值
     *
     * @param key    键名
     * @param member 成员
     * @return 分值；成员不存在或键不存在时返回 null
     */
    public Double zscore(String key, String member) {
        return redisTemplate.opsForZSet().score(key, member);
    }

    /**
     * 统计有序集合成员数
     *
     * @param key 键名
     * @return 成员数；键不存在时返回 0
     */
    public long zcard(String key) {
        Long size = redisTemplate.opsForZSet().zCard(key);
        return size == null ? 0L : size;
    }

    /**
     * 按排名区间移除有序集合成员（用于偏好特征按分值截断尾部）
     *
     * @param key   键名
     * @param start 起始下标
     * @param end   结束下标
     */
    public void zremrangeByRank(String key, long start, long end) {
        redisTemplate.opsForZSet().removeRange(key, start, end);
    }

    // ==================== 哈希（特征元信息、商品可售状态） ====================

    /**
     * 写入哈希单个字段
     *
     * @param key   键名
     * @param field 字段名
     * @param value 字段值
     */
    public void hset(String key, String field, String value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 批量写入哈希字段
     *
     * @param key  键名
     * @param data 字段与值的映射
     */
    public void hsetAll(String key, Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        redisTemplate.opsForHash().putAll(key, data);
    }

    /**
     * 哈希字段自增
     *
     * <p>业务语义：按来源维度累计浏览总数与「已采集停留时长」的浏览数，用于爬虫判定的采集率统计。
     *
     * @param key   键名
     * @param field 字段名
     * @param delta 增量
     * @return 自增后的值
     */
    public long hincrBy(String key, String field, long delta) {
        Long value = redisTemplate.opsForHash().increment(key, field, delta);
        return value == null ? 0L : value;
    }

    /**
     * 读取哈希单个字段
     *
     * @param key   键名
     * @param field 字段名
     * @return 字段值；不存在时返回 null
     */
    public String hget(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        return value == null ? null : value.toString();
    }

    /**
     * 读取哈希全部字段
     *
     * @param key 键名
     * @return 字段与值的映射；键不存在时返回空映射
     */
    public Map<Object, Object> hgetAll(String key) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        return entries == null ? Collections.emptyMap() : entries;
    }

    // ==================== 集合（过滤清单、排除清单） ====================

    /**
     * 向集合批量添加成员
     *
     * @param key     键名
     * @param members 成员数组
     */
    public void sadd(String key, String... members) {
        if (members == null || members.length == 0) {
            return;
        }
        redisTemplate.opsForSet().add(key, members);
    }

    /**
     * 向集合批量添加成员
     *
     * @param key     键名
     * @param members 成员集合
     */
    public void saddAll(String key, Collection<String> members) {
        if (members == null || members.isEmpty()) {
            return;
        }
        redisTemplate.opsForSet().add(key, members.toArray(new String[0]));
    }

    /**
     * 读取集合全部成员
     *
     * @param key 键名
     * @return 成员集合；键不存在时返回空集合
     */
    public Set<String> smembers(String key) {
        Set<String> members = redisTemplate.opsForSet().members(key);
        return members == null ? Collections.emptySet() : members;
    }

    /**
     * 判断成员是否属于集合
     *
     * @param key    键名
     * @param member 成员
     * @return 属于返回 true
     */
    public boolean sismember(String key, String member) {
        Boolean member0 = redisTemplate.opsForSet().isMember(key, member);
        return Boolean.TRUE.equals(member0);
    }

    // ==================== 字符串（快照、去重、限频、配置副本） ====================

    /**
     * 写入字符串值（不设有效期）
     *
     * @param key   键名
     * @param value 值
     */
    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 写入字符串值并设置有效期
     *
     * @param key        键名
     * @param value      值
     * @param ttlSeconds 有效期（秒）
     */
    public void setex(String key, String value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 读取字符串值
     *
     * @param key 键名
     * @return 值；键不存在时返回 null
     */
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 仅当键不存在时写入（幂等去重场景）
     *
     * @param key        键名
     * @param value      值
     * @param ttlSeconds 有效期（秒）
     * @return 写入成功返回 true，键已存在返回 false
     */
    public boolean setIfAbsent(String key, String value, long ttlSeconds) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 自增计数（限频场景）
     *
     * @param key   键名
     * @param delta 增量
     * @return 自增后的值
     */
    public long increment(String key, long delta) {
        Long value = redisTemplate.opsForValue().increment(key, delta);
        return value == null ? 0L : value;
    }

    // ==================== 通用 ====================

    /**
     * 设置键的有效期（用于续期：偏好特征每次汇总写入后续期）
     *
     * @param key        键名
     * @param ttlSeconds 有效期（秒）
     * @return 设置成功返回 true
     */
    public boolean expire(String key, long ttlSeconds) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS));
    }

    /**
     * 判断键是否存在
     *
     * @param key 键名
     * @return 存在返回 true
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 删除键
     *
     * @param key 键名
     * @return 删除成功返回 true
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }
}
