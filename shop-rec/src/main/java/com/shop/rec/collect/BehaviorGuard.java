package com.shop.rec.collect;

import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.BehaviorTypes;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.model.dto.BehaviorReportDTO;
import com.shop.rec.service.RecConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 行为采集防护（M-01 步骤 4~7）
 *
 * <p>
 * 承担幂等去重、两级限频、无效行为识别、爬虫判定与采样五类防护，对应《详细设计说明书》
 * 3.1 的业务处理步骤与异常分支表。任一环节命中即丢弃并计入指标，不阻塞后续请求。
 *
 * <p>
 * <b>关于无停留时长的处理（防误杀要点）</b>：{@code duration} 是可选字段，前端可能整体未启用上报。
 * 若不加区分地把"无停留时长"直接作为无效或爬虫判据，在共用出口网络（NAT、校园网、办公网）下会
 * 整片误杀真实用户。因此：
 * <ul>
 *     <li>无效行为识别：仅在 duration 有值时才判定；</li>
 *     <li>爬虫判定：先校验该来源的 duration 采集率，采集率低于下限时改用纯频次判据，
 *         并对低采集率来源告警以提示排查埋点配置。</li>
 * </ul>
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorGuard {

    /** 无效行为识别：浏览停留时长阈值（毫秒），低于该值且短时间内重复浏览同商品判为无效 */
    private static final int MIN_VIEW_DURATION_MS = 1000;

    /** 爬虫判定：同来源对同商品的分钟级浏览次数基础阈值 */
    private static final long CRAWLER_BROWSE_THRESHOLD = 20L;

    /** 爬虫判定：来源 duration 采集率下限，低于此值时"无停留时长"不再作为判据 */
    private static final double CRAWLER_DURATION_RATE_FLOOR = 0.5D;

    /** 爬虫判定：采集率不足时采用的替代判据——来源分钟级请求数阈值 */
    private static final long CRAWLER_REQUEST_THRESHOLD = 60L;

    /** 采样判定的取值基数：按事件标识哈希取模后与采样阈值比较 */
    private static final int SAMPLE_BUCKETS = 1000;

    private final RecRedis recRedis;

    private final RecConfigService configService;

    /**
     * 执行采集防护校验
     *
     * <p>校验顺序：幂等去重 → 来源封禁 → 用户级限频 → 单商品限频 → 无效行为 → 爬虫判定 → 采样。
     * 封禁检查提前，避免已封禁来源继续消耗限频计数。
     *
     * @param dto    上报请求
     * @param userId 用户标识（由服务端从登录态解析）
     * @param source 来源标识（可选，缺失时跳过爬虫判定）
     * @return 命中的丢弃原因；全部通过返回 null
     */
    public CollectReject check(BehaviorReportDTO dto, Long userId, String source) {
        if (isDuplicated(dto.getEventId())) {
            return CollectReject.DUPLICATED;
        }
        if (isSourceBanned(source, dto.getEventType())) {
            return CollectReject.CRAWLER;
        }
        if (isUserLimited(userId)) {
            return CollectReject.LIMIT_USER;
        }
        if (isItemLimited(userId, dto)) {
            return CollectReject.LIMIT_ITEM;
        }
        if (isInvalidView(dto, userId)) {
            return CollectReject.INVALID_BEHAVIOR;
        }
        if (isCrawler(dto, source)) {
            return CollectReject.CRAWLER;
        }
        if (isSampledOut(dto.getEventId())) {
            return CollectReject.SAMPLED;
        }
        return null;
    }

    /**
     * 幂等去重：以事件标识执行 SETNX，键已存在即判为重复上报
     *
     * @param eventId 事件幂等标识
     * @return 重复返回 true
     */
    private boolean isDuplicated(String eventId) {
        return !recRedis.setIfAbsent(RecRedisKeys.dedup(eventId), "1", RecRedisKeys.TTL_DEDUP);
    }

    /**
     * 来源封禁检查：命中爬虫的来源在封禁期内的浏览事件全部丢弃
     *
     * @param source    来源标识
     * @param eventType 行为类型
     * @return 已封禁返回 true
     */
    private boolean isSourceBanned(String source, String eventType) {
        if (source == null || source.isBlank() || !BehaviorTypes.VIEW.equals(eventType)) {
            return false;
        }
        return recRedis.exists(RecRedisKeys.crawlerBan(source));
    }

    /**
     * 用户级限频：单用户每分钟上报次数上限
     *
     * @param userId 用户标识
     * @return 超限返回 true
     */
    private boolean isUserLimited(Long userId) {
        long count = increaseWithTtl(RecRedisKeys.limitReport(userId), RecRedisKeys.TTL_LIMIT_REPORT);
        int limit = configService.getInt(RecConfigKeys.LIMIT_REPORT_PER_MINUTE);
        return limit > 0 && count > limit;
    }

    /**
     * 单商品限频：单用户单商品单行为类型每分钟上报次数上限
     *
     * @param userId 用户标识
     * @param dto    上报请求
     * @return 超限返回 true
     */
    private boolean isItemLimited(Long userId, BehaviorReportDTO dto) {
        String key = RecRedisKeys.limitItem(userId, dto.getItemId(), dto.getEventType());
        long count = increaseWithTtl(key, RecRedisKeys.TTL_LIMIT_REPORT);
        int limit = configService.getInt(RecConfigKeys.LIMIT_REPORT_ITEM_PER_MINUTE);
        return limit > 0 && count > limit;
    }

    /**
     * 无效行为识别：停留时长小于阈值，且 5 秒内该用户已浏览过同一商品
     *
     * <p>同时承担"最近浏览标记"的写入——无论是否判定为无效，都要刷新该标记。
     *
     * @param dto    上报请求
     * @param userId 用户标识
     * @return 判为无效返回 true
     */
    private boolean isInvalidView(BehaviorReportDTO dto, Long userId) {
        if (!BehaviorTypes.VIEW.equals(dto.getEventType())) {
            return false;
        }
        String recentKey = RecRedisKeys.recentView(userId, dto.getItemId());
        boolean hasRecentView = recRedis.exists(recentKey);
        recRedis.setex(recentKey, "1", RecRedisKeys.TTL_RECENT_VIEW);

        Integer duration = dto.getDuration();
        // 未采集停留时长时不做无效判定：字段可选，缺失不等于停留时间为零
        if (duration == null) {
            return false;
        }
        return duration < MIN_VIEW_DURATION_MS && hasRecentView;
    }

    /**
     * 爬虫判定
     *
     * <p>基础条件为「同来源 1 分钟内对同商品浏览超过阈值」，但仅此不足以判定：
     * 必须叠加 duration 采集率前置校验，采集率低于下限时改用纯频次判据，
     * 避免前端未启用 duration 埋点时把正常用户整片误杀。命中后封禁该来源的浏览事件。
     *
     * @param dto    上报请求
     * @param source 来源标识
     * @return 判为爬虫返回 true
     */
    private boolean isCrawler(BehaviorReportDTO dto, String source) {
        if (source == null || source.isBlank() || !BehaviorTypes.VIEW.equals(dto.getEventType())) {
            return false;
        }
        String countKey = RecRedisKeys.crawlerCount(source, dto.getItemId());
        long browseCount = increaseWithTtl(countKey, RecRedisKeys.TTL_CRAWLER_WINDOW);
        if (browseCount <= CRAWLER_BROWSE_THRESHOLD) {
            return false;
        }

        String statKey = RecRedisKeys.crawlerStat(source);
        long total = recRedis.hincrBy(statKey, RecRedisKeys.CRAWLER_FIELD_TOTAL, 1L);
        if (dto.getDuration() != null) {
            recRedis.hincrBy(statKey, RecRedisKeys.CRAWLER_FIELD_WITH_DURATION, 1L);
        }
        recRedis.expire(statKey, RecRedisKeys.TTL_CRAWLER_WINDOW);
        long withDuration = parseLong(recRedis.hget(statKey, RecRedisKeys.CRAWLER_FIELD_WITH_DURATION));
        double durationRate = total == 0L ? 0D : (double) withDuration / total;

        boolean crawler;
        if (durationRate < CRAWLER_DURATION_RATE_FLOOR) {
            // 采集率过低：改用纯频次判据（duration 埋点缺失时"无停留时长"不具区分力）
            crawler = browseCount > CRAWLER_REQUEST_THRESHOLD;
            if (crawler) {
                log.warn("来源 duration 采集率偏低({}%)，改用频次判定命中爬虫, source: {}, 分钟浏览次数: {}, 建议排查前端埋点配置",
                        Math.round(durationRate * 100), source, browseCount);
            }
        } else {
            crawler = dto.getDuration() == null;
        }

        if (crawler) {
            recRedis.setex(RecRedisKeys.crawlerBan(source), "1", RecRedisKeys.TTL_CRAWLER_BAN);
            log.warn("来源命中爬虫判定并封禁 {} 秒, source: {}, 商品: {}, 分钟浏览次数: {}",
                    RecRedisKeys.TTL_CRAWLER_BAN, source, dto.getItemId(), browseCount);
        }
        return crawler;
    }

    /**
     * 采样判定：按事件标识哈希取模与采样阈值比较，保证同一事件的判定结果稳定
     *
     * @param eventId 事件幂等标识
     * @return 被采样式丢弃返回 true
     */
    private boolean isSampledOut(String eventId) {
        double sampleRate = configService.getDouble(RecConfigKeys.COLLECT_SAMPLE_RATE);
        if (sampleRate >= 1.0D) {
            return false;
        }
        if (sampleRate <= 0D) {
            return true;
        }
        int bucket = Math.abs(eventId.hashCode() % SAMPLE_BUCKETS);
        return bucket >= (int) (sampleRate * SAMPLE_BUCKETS);
    }

    /**
     * 计数并设置有效期（仅在计数为 1 时设置，保证窗口从首次请求开始计）
     *
     * @param key        键名
     * @param ttlSeconds 有效期（秒）
     * @return 自增后的计数值
     */
    private long increaseWithTtl(String key, long ttlSeconds) {
        long count = recRedis.increment(key, 1L);
        if (count == 1L) {
            recRedis.expire(key, ttlSeconds);
        }
        return count;
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
