package com.shop.rec.fusion;

import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.enums.DegradeState;
import com.shop.rec.fusion.model.FilterResult;
import com.shop.rec.fusion.model.FilteredCandidate;
import com.shop.rec.item.ItemDataService;
import com.shop.rec.recall.model.RecallCandidate;
import com.shop.rec.recall.model.RecallResult;
import com.shop.rec.service.RecConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合与过滤（M-06）
 *
 * <p>
 * 严格对应《详细设计说明书》3.6：两路候选合并去重 → 依次执行过滤规则 → 形成召回候选池 →
 * 候选不足时用兜底名单补位 → 统计各原因过滤数量。
 *
 * <p>
 * <b>过滤规则执行顺序</b>：上下架与库存 → 黑名单 → 近 N 天已购与已加购 → 地域不可售 →
 * 会员限定 → 低质商品。后三类依赖商城侧当前不存在的字段或未定的业务口径，
 * 本期只保证规则通路可配置可热更新、<b>不纳入验收</b>（详见概要设计 5.4）。
 *
 * <p>
 * <b>状态不可确认的处理</b>：商品可售状态缓存缺失且回源失败时丢弃该候选，
 * 而不是放行——推荐结果一旦展示下架商品即为体验事故（详细设计 3.6 异常分支）。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class FusionFilterService {

    /** 已购与已加购排除规则的配置键：排除天数，取 0 表示不启用 */
    private static final String RULE_BLACKLIST = "blacklist";

    /** 过滤原因统计键 */
    private static final String STAT_NOT_SELLABLE = "notSellable";

    private static final String STAT_STATUS_UNKNOWN = "statusUnknown";

    private static final String STAT_BLACKLIST = "blacklist";

    private static final String STAT_RECENT_EXCLUDED = "recentExcluded";

    private static final String STAT_REGION_LIMIT = "regionLimit";

    private static final String STAT_MEMBER_ONLY = "memberOnly";

    private static final String STAT_LOW_QUALITY = "lowQuality";

    /** 商品上架状态取值 */
    private static final String ITEM_STATUS_ON_SALE = "1";

    private final RecRedis recRedis;

    private final RecConfigService configService;

    private final ItemDataService itemDataService;

    /**
     * 融合与过滤
     *
     * @param recall 召回结果
     * @param userId 用户标识（已购已加购等用户维度过滤规则使用）
     * @return 候选池、过滤统计与命中的降级状态
     */
    public FilterResult fuseAndFilter(RecallResult recall, Long userId) {
        FilterResult result = new FilterResult();
        Map<String, Integer> stats = new LinkedHashMap<>();
        result.setFilterStats(stats);

        // 步骤 1~2：两路候选归一化后合并去重
        List<RecallCandidate> categoryCandidates = normalize(recall.getCategoryCandidates());
        List<RecallCandidate> hotCandidates = normalize(recall.getHotCandidates());
        Map<Long, FilteredCandidate> merged = merge(categoryCandidates, hotCandidates);

        // 步骤 3~8：依次执行六类过滤
        List<FilteredCandidate> candidates = new ArrayList<>(merged.size());
        for (FilteredCandidate candidate : merged.values()) {
            if (shouldFilter(candidate, userId, stats)) {
                continue;
            }
            candidates.add(candidate);
        }

        // 步骤 9：候选不足时用兜底名单补位
        int targetSize = resolvePoolSize();
        if (candidates.size() < targetSize) {
            supplementFromFallback(candidates, targetSize);
        }

        // 步骤 10：候选池为空 → 标记兜底名单，由 M-08 统一裁决对外状态
        if (candidates.isEmpty()) {
            result.setDegradeState(DegradeState.FALLBACK_LIST);
            log.warn("过滤后候选池为空, userId: {}, 各路候选数: {}, 过滤统计: {}",
                    userId, recall.totalSize(), stats);
        } else if (recall.getDegradeState() != null && recall.getDegradeState() != DegradeState.NORMAL) {
            // 保留召回环节的降级状态，由 M-08 按优先级裁决
            result.setDegradeState(recall.getDegradeState());
        }

        result.setCandidates(candidates);
        return result;
    }

    /**
     * 各路内 min-max 归一化
     *
     * <p>类目偏好路与热门路的分值量纲不同，须先归一到同一区间才能跨路比较。
     * 全路同分时统一取 0.5，避免除零且不产生区分作用（与打分环节的归一化约定一致）。
     *
     * @param candidates 某一路候选
     * @return 归一化后的候选（原地更新分值，返回同一列表）
     */
    private List<RecallCandidate> normalize(List<RecallCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        double max = candidates.stream().mapToDouble(RecallCandidate::getRecallScore).max().orElse(0D);
        double min = candidates.stream().mapToDouble(RecallCandidate::getRecallScore).min().orElse(0D);
        double range = max - min;
        for (RecallCandidate candidate : candidates) {
            candidate.setRecallScore(range == 0D ? 0.5D : (candidate.getRecallScore() - min) / range);
        }
        return candidates;
    }

    /**
     * 合并两路候选并去重
     *
     * <p>以商品标识为键，同一商品命中多路时保留较大的归一化召回分，并记录来源集合
     * （详细设计 6.3 融合与去重规则）。
     *
     * @param categoryCandidates 类目偏好路（已归一化）
     * @param hotCandidates      热门路（已归一化）
     * @return 去重后的候选映射（保持插入顺序，结果稳定）
     */
    private Map<Long, FilteredCandidate> merge(List<RecallCandidate> categoryCandidates,
                                               List<RecallCandidate> hotCandidates) {
        Map<Long, FilteredCandidate> merged = new LinkedHashMap<>();
        putAll(merged, categoryCandidates);
        putAll(merged, hotCandidates);
        return merged;
    }

    /**
     * 把某一路候选并入结果集
     *
     * @param merged     结果集
     * @param candidates 该路候选
     */
    private void putAll(Map<Long, FilteredCandidate> merged, List<RecallCandidate> candidates) {
        for (RecallCandidate candidate : candidates) {
            FilteredCandidate existing = merged.get(candidate.getItemId());
            if (existing == null) {
                FilteredCandidate filtered = new FilteredCandidate();
                filtered.setItemId(candidate.getItemId());
                filtered.setNormalizedScore(candidate.getRecallScore());
                filtered.setCategoryId(candidate.getCategoryId());
                filtered.getSources().add(candidate.getSource());
                merged.put(candidate.getItemId(), filtered);
            } else {
                existing.getSources().add(candidate.getSource());
                if (candidate.getRecallScore() > existing.getNormalizedScore()) {
                    existing.setNormalizedScore(candidate.getRecallScore());
                }
                if (existing.getCategoryId() == null) {
                    existing.setCategoryId(candidate.getCategoryId());
                }
            }
        }
    }

    /**
     * 执行六类过滤规则
     *
     * @param candidate 待判定的候选
     * @param userId    用户标识
     * @param stats     过滤原因统计
     * @return 需要过滤掉返回 true
     */
    private boolean shouldFilter(FilteredCandidate candidate, Long userId, Map<String, Integer> stats) {
        Long itemId = candidate.getItemId();

        // 规则一：上下架与库存（状态不可确认同样丢弃）
        Map<Object, Object> itemState = recRedis.hgetAll(RecRedisKeys.itemState(itemId));
        if (itemState.isEmpty()) {
            count(stats, STAT_STATUS_UNKNOWN);
            return true;
        }
        String status = String.valueOf(itemState.get(RecRedisKeys.ITEM_STATE_FIELD_STATUS));
        String stock = String.valueOf(itemState.get(RecRedisKeys.ITEM_STATE_FIELD_STOCK));
        if (!ITEM_STATUS_ON_SALE.equals(status) || "0".equals(stock)) {
            count(stats, STAT_NOT_SELLABLE);
            return true;
        }
        // 状态缓存已读取，顺带保留品牌标识供打分环节使用（避免二次访问内存库）
        candidate.setBrandId(parseLong(String.valueOf(itemState.get(RecRedisKeys.ITEM_STATE_FIELD_BRAND))));

        // 规则二：黑名单
        if (recRedis.sismember(RecRedisKeys.filter(RULE_BLACKLIST), String.valueOf(itemId))) {
            count(stats, STAT_BLACKLIST);
            return true;
        }

        // 规则三：近 N 天已购与已加购（排除天数置 0 时不启用）
        int excludeDays = configService.getInt(RecConfigKeys.EXCLUDE_RECENT_DAYS);
        if (excludeDays > 0 && userId != null
                && recRedis.sismember(RecRedisKeys.exclude(userId), String.valueOf(itemId))) {
            count(stats, STAT_RECENT_EXCLUDED);
            return true;
        }

        // 规则四~六：地域不可售 / 会员限定 / 低质商品
        // 依赖商城侧当前不存在的字段或未定的业务口径，本期只保留规则通路，不纳入验收
        if (recRedis.sismember(RecRedisKeys.filter("region_limit"), String.valueOf(itemId))) {
            count(stats, STAT_REGION_LIMIT);
            return true;
        }
        if (recRedis.sismember(RecRedisKeys.filter("member_only"), String.valueOf(itemId))) {
            count(stats, STAT_MEMBER_ONLY);
            return true;
        }
        if (recRedis.sismember(RecRedisKeys.filter("low_quality"), String.valueOf(itemId))) {
            count(stats, STAT_LOW_QUALITY);
            return true;
        }

        return false;
    }

    /**
     * 用兜底名单补足候选池
     *
     * <p>补位商品若已在候选池中则跳过，避免重复（详细设计 3.6 步骤 9）。
     *
     * @param candidates 当前候选池
     * @param targetSize 目标规模
     */
    private void supplementFromFallback(List<FilteredCandidate> candidates, int targetSize) {
        List<Long> fallbackItems = itemDataService.getFallbackItems();
        if (fallbackItems.isEmpty()) {
            return;
        }
        java.util.Set<Long> existing = new java.util.HashSet<>();
        for (FilteredCandidate candidate : candidates) {
            existing.add(candidate.getItemId());
        }
        for (Long itemId : fallbackItems) {
            if (candidates.size() >= targetSize) {
                return;
            }
            if (existing.contains(itemId)) {
                continue;
            }
            FilteredCandidate supplement = new FilteredCandidate();
            supplement.setItemId(itemId);
            supplement.setNormalizedScore(0D);
            supplement.getSources().add(RecallCandidate.SOURCE_FALLBACK);
            candidates.add(supplement);
            existing.add(itemId);
        }
    }

    /**
     * 解析候选池目标规模
     *
     * @return 目标规模；配置异常时取 200
     */
    private int resolvePoolSize() {
        int poolSize = configService.getInt(RecConfigKeys.RECALL_POOL_SIZE);
        return poolSize > 0 ? poolSize : 200;
    }

    /**
     * 过滤原因计数
     *
     * @param stats 统计容器
     * @param key   原因键
     */
    private void count(Map<String, Integer> stats, String key) {
        stats.merge(key, 1, Integer::sum);
    }

    /**
     * 字符串转长整型，解析失败或为空返回 null
     *
     * @param value 原始值
     * @return 数值；无效时返回 null
     */
    private Long parseLong(String value) {
        if (value == null || "null".equals(value) || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
