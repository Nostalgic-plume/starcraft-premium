package com.shop.rec.rank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.enums.DegradeState;
import com.shop.rec.fusion.model.FilterResult;
import com.shop.rec.fusion.model.FilteredCandidate;
import com.shop.rec.mapper.RecFixedSlotMapper;
import com.shop.rec.model.entity.RecFixedSlotDO;
import com.shop.rec.rank.model.RankedItem;
import com.shop.rec.rank.model.RankResult;
import com.shop.rec.recall.model.RecallCandidate;
import com.shop.rec.service.RecConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个性化重排与固定坑位注入（M-07）
 *
 * <p>
 * 严格对应《详细设计说明书》3.7 与 6.4：取三个打分因子 → 候选池内 min-max 归一化 →
 * 按配置权值加权求和 → 三级排序 → 注入固定坑位 → 输出顺序稳定的完整榜单。
 *
 * <p>
 * <b>打分公式</b>：{@code Score = w_cat × norm(类目偏好) + w_hot × norm(热度) + w_brand × norm(品牌偏好)}，
 * 默认权值 0.6 / 0.4 / 0，三者之和为 1（由配置校验保证）。
 *
 * <p>
 * <b>池内归一化的取舍</b>：采用候选池内相对归一化而非全局归一化，可保证同一用户不同时间、
 * 同一时间不同用户之间的打分口径稳定，同时避免引入全局统计成本（详细设计 6.4(6)）。
 * 需注意该方式在候选池过大时会削弱用户间区分度——调参建议见详细设计 6.4(7)。
 *
 * <p>
 * <b>排序稳定性</b>：总分降序 → 热度降序 → 商品标识升序。第三级比较保证同分商品顺序稳定，
 * 避免同一快照内出现顺序抖动。
 *
 * <p>
 * <b>固定坑位</b>：坑位商品不参与个性化打分与重排，按位置强制占位；
 * 坑位商品不可售或不存在时跳过该坑位，位置由后续商品自然补位（详细设计 6.4(5)）。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class RankService {

    /** 坑位缓存时长（毫秒）：避免每次请求都查库 */
    private static final long SLOT_CACHE_MILLIS = 10_000L;

    /** 固定坑位启用状态 */
    private static final int SLOT_STATUS_ENABLED = 1;

    private final RecRedis recRedis;

    private final RecConfigService configService;

    private final RecFixedSlotMapper recFixedSlotMapper;

    /** 生效坑位缓存：位置 → 商品标识 */
    private volatile Map<Integer, Long> slotCache = new LinkedHashMap<>();

    private volatile long slotCacheAt = 0L;

    /**
     * 执行重排
     *
     * @param filter 融合过滤结果
     * @param userId 用户标识
     * @return 榜单与命中的降级状态
     */
    public RankResult rank(FilterResult filter, Long userId) {
        List<FilteredCandidate> candidates = filter.getCandidates();
        RankResult result = new RankResult();
        if (candidates == null || candidates.isEmpty()) {
            result.setDegradeState(filter.getDegradeState());
            return result;
        }

        try {
            List<ScoreItem> scoreItems = buildScoreItems(candidates, userId);
            normalize(scoreItems);
            applyWeights(scoreItems);
            sortItems(scoreItems);

            List<RankedItem> ranked = toRankedItems(scoreItems);
            // 顺序不可调换：配额必须在坑位注入之前，运营坑位优先级高于算法位置安排
            ranked = applyHeadQuota(ranked);
            ranked = injectFixedSlots(ranked);
            result.setItems(ranked);
            result.setDegradeState(filter.getDegradeState());
            return result;
        } catch (Exception e) {
            // 打分异常时回退为按召回热度降序，保证榜单非空（详细设计 3.7 异常分支）
            log.error("打分过程异常，回退为按热度降序排列, userId: {}", userId, e);
            result.setItems(fallbackByHeat(candidates));
            result.setDegradeState(DegradeState.FEATURE_MISSING);
            return result;
        }
    }

    /**
     * 为每个候选取三个打分因子的原始值
     *
     * <p>单商品缺分值按 0 计（详细设计 3.7 步骤 1）。
     *
     * @param candidates 候选池
     * @param userId     用户标识
     * @return 打分上下文列表
     */
    private List<ScoreItem> buildScoreItems(List<FilteredCandidate> candidates, Long userId) {
        Map<Long, Double> categoryPrefs = readZset(RecRedisKeys.userCatPref(userId));
        Map<Long, Double> brandPrefs = readZset(RecRedisKeys.userBrandPref(userId));
        Map<Long, Double> heatMap = readZset(RecRedisKeys.HOT_ITEM);

        List<ScoreItem> scoreItems = new ArrayList<>(candidates.size());
        for (FilteredCandidate candidate : candidates) {
            ScoreItem item = new ScoreItem();
            item.candidate = candidate;
            item.categoryRaw = candidate.getCategoryId() == null
                    ? 0D : categoryPrefs.getOrDefault(candidate.getCategoryId(), 0D);
            item.heatRaw = heatMap.getOrDefault(candidate.getItemId(), 0D);
            item.brandRaw = candidate.getBrandId() == null
                    ? 0D : brandPrefs.getOrDefault(candidate.getBrandId(), 0D);
            scoreItems.add(item);
        }
        return scoreItems;
    }

    /**
     * 候选池内 min-max 归一化
     *
     * <p>某因子在池内最大值等于最小值（全池同分或缺值）时统一取 0.5，避免除零且使该因子
     * 对排序不产生区分作用（详细设计 6.4(2)）。
     *
     * @param scoreItems 打分上下文
     */
    private void normalize(List<ScoreItem> scoreItems) {
        double catMin = scoreItems.stream().mapToDouble(i -> i.categoryRaw).min().orElse(0D);
        double catMax = scoreItems.stream().mapToDouble(i -> i.categoryRaw).max().orElse(0D);
        double hotMin = scoreItems.stream().mapToDouble(i -> i.heatRaw).min().orElse(0D);
        double hotMax = scoreItems.stream().mapToDouble(i -> i.heatRaw).max().orElse(0D);
        double brandMin = scoreItems.stream().mapToDouble(i -> i.brandRaw).min().orElse(0D);
        double brandMax = scoreItems.stream().mapToDouble(i -> i.brandRaw).max().orElse(0D);

        for (ScoreItem item : scoreItems) {
            item.normCategory = minMax(item.categoryRaw, catMin, catMax);
            item.normHeat = minMax(item.heatRaw, hotMin, hotMax);
            item.normBrand = minMax(item.brandRaw, brandMin, brandMax);
        }
    }

    /**
     * 按配置权值计算总分
     *
     * @param scoreItems 打分上下文
     */
    private void applyWeights(List<ScoreItem> scoreItems) {
        double weightCategory = resolveWeight(RecConfigKeys.RANK_WEIGHT_CAT, 0.6D);
        double weightHeat = resolveWeight(RecConfigKeys.RANK_WEIGHT_HOT, 0.4D);
        double weightBrand = resolveWeight(RecConfigKeys.RANK_WEIGHT_BRAND, 0D);

        for (ScoreItem item : scoreItems) {
            item.total = weightCategory * item.normCategory
                    + weightHeat * item.normHeat
                    + weightBrand * item.normBrand;
        }
    }

    /**
     * 三级排序：总分降序 → 热度降序 → 商品标识升序
     *
     * @param scoreItems 打分上下文
     */
    private void sortItems(List<ScoreItem> scoreItems) {
        scoreItems.sort(Comparator
                .comparingDouble((ScoreItem item) -> item.total).reversed()
                .thenComparing(Comparator.comparingDouble((ScoreItem item) -> item.heatRaw).reversed())
                .thenComparing(item -> item.candidate.getItemId()));
    }

    /**
     * 转换为榜单元素
     *
     * @param scoreItems 已排序的打分上下文
     * @return 榜单元素列表
     */
    private List<RankedItem> toRankedItems(List<ScoreItem> scoreItems) {
        List<RankedItem> ranked = new ArrayList<>(scoreItems.size());
        for (ScoreItem item : scoreItems) {
            ranked.add(new RankedItem(item.candidate.getItemId(),
                    primarySource(item.candidate),
                    item.total,
                    false));
        }
        return ranked;
    }

    /**
     * 取候选的主力来源标记
     *
     * <p>来源优先级：类目偏好 &gt; 热门 &gt; 兜底（详细设计 6.3 来源标记优先级，
     * 固定坑位不来自候选池，单独标记）。
     *
     * @param candidate 候选
     * @return 来源标记
     */
    private String primarySource(FilteredCandidate candidate) {
        Set<String> sources = candidate.getSources();
        if (sources.contains(RecallCandidate.SOURCE_CATEGORY_PREF)) {
            return RecallCandidate.SOURCE_CATEGORY_PREF;
        }
        if (sources.contains(RecallCandidate.SOURCE_HOT)) {
            return RecallCandidate.SOURCE_HOT;
        }
        return RecallCandidate.SOURCE_FALLBACK;
    }

    /**
     * 榜单首位个性化配额（前 N 位改用类目偏好路候选）
     *
     * <p>把前 N 位换成类目偏好路召回的商品，其余位次保持原融合顺序顺延，满足「首页前 N 条为
     * 纯个性化」的产品要求。N 由配置项 {@link RecConfigKeys#RANK_HEAD_PERSONALIZED_SIZE} 控制，
     * <b>置 0 即关闭</b>。
     *
     * <p><b>为什么按融合总分取前列、而不是按类目偏好原始分重排</b>：类目偏好路的候选本就在
     * 融合前按该路召回分排序，取融合后的前列即取该路最靠前的商品；若改用 {@code categoryRaw}
     * 重排，会绕开热度的 40% 权重，让前 N 位完全脱离热度校验。复用融合分还保证配额内外的
     * 打分口径一致——变的只是「谁占前 N 位」。
     *
     * <p><b>⚠️ 本步骤有意打破「总分降序」不变量</b>：配额之后，第 N 名的融合总分可能低于
     * 第 N+1 名。这是需求要求的效果，不是缺陷——对照 {@link #sortItems} 的「排序稳定性」
     * 说明时切勿当成回归修掉。需要关闭把配置项置 0。
     *
     * <p><b>不足 N 条不补空位</b>：冷启动用户与游客没有类目偏好候选，类目偏好路经六条过滤规则
     * 后也可能不足 N 条。此时有多少用多少，剩余位次由融合顺序自然填充，榜单长度不变。
     *
     * @param ranked 已按融合总分降序的榜单
     * @return 配额后的榜单；配额关闭或无类目偏好候选时原样返回
     */
    private List<RankedItem> applyHeadQuota(List<RankedItem> ranked) {
        int quota = configService.getInt(RecConfigKeys.RANK_HEAD_PERSONALIZED_SIZE);
        if (quota <= 0 || ranked.size() <= 1) {
            return ranked;
        }

        List<RankedItem> head = new ArrayList<>(quota);
        List<RankedItem> rest = new ArrayList<>(ranked.size());
        for (RankedItem item : ranked) {
            if (head.size() < quota
                    && RecallCandidate.SOURCE_CATEGORY_PREF.equals(item.getRecallSource())) {
                head.add(item);
            } else {
                rest.add(item);
            }
        }

        if (head.isEmpty()) {
            // 类目偏好路无候选（冷启动 / 游客 / 该路降级），保持原融合顺序
            return ranked;
        }

        head.addAll(rest);
        return head;
    }

    /**
     * 注入固定坑位
     *
     * <p>按位置把坑位商品放入榜单对应位置，其余商品顺序顺延；坑位商品若已在榜单中则先从原位置移除，
     * 避免重复曝光（详细设计 6.4(5)）。
     *
     * <p><b>坑位可售校验（详细设计 3.7 步骤 8）</b>：坑位商品不来自候选池，未经过 M-06 的六类过滤，
     * 因此必须单独校验可售状态——否则运营配置一个已下架商品，它会绕过全部过滤规则直接出现在榜单
     * 指定位置。不可售或状态不可确认的坑位直接跳过，位置由后续商品自然补位。
     *
     * @param ranked 榜单
     * @return 注入坑位后的榜单
     */
    private List<RankedItem> injectFixedSlots(List<RankedItem> ranked) {
        Map<Integer, Long> slots = loadActiveSlots();
        if (slots.isEmpty()) {
            return ranked;
        }

        // 只有可售坑位参与注入；被跳过的坑位不占用位置，后续商品顺延补位
        Map<Integer, Long> sellableSlots = filterSellableSlots(slots);
        if (sellableSlots.isEmpty()) {
            return ranked;
        }

        Set<Long> slotItemIds = new HashSet<>(sellableSlots.values());
        List<RankedItem> remaining = new ArrayList<>(ranked.size());
        for (RankedItem item : ranked) {
            if (!slotItemIds.contains(item.getItemId())) {
                remaining.add(item);
            }
        }

        List<RankedItem> result = new ArrayList<>(ranked.size());
        Iterator<RankedItem> iterator = remaining.iterator();
        int total = ranked.size();
        for (int position = 1; position <= total; position++) {
            Long slotItemId = sellableSlots.get(position);
            if (slotItemId != null) {
                result.add(new RankedItem(slotItemId, RankedItem.SOURCE_FIXED_SLOT, 0D, true));
            } else if (iterator.hasNext()) {
                result.add(iterator.next());
            } else if (result.size() < total) {
                // 坑位占用了额外位置，尾部无商品可补时提前结束
                break;
            }
        }
        return result;
    }

    /**
     * 过滤出可售的坑位
     *
     * <p>状态读取的是推荐库商品状态缓存（由 M-04 维护），与 M-06 过滤环节同一数据源。
     * 读取失败或缓存缺失一律按不可确认处理并跳过——坑位是运营强插位，宁可少一个坑位，
     * 也不能把下架或售罄商品推到用户面前。
     *
     * @param slots 位置到商品标识的映射
     * @return 可售坑位映射；全部不可售时返回空映射
     */
    private Map<Integer, Long> filterSellableSlots(Map<Integer, Long> slots) {
        Map<Integer, Long> sellable = new LinkedHashMap<>(slots.size());
        for (Map.Entry<Integer, Long> entry : slots.entrySet()) {
            if (isSellable(entry.getValue())) {
                sellable.put(entry.getKey(), entry.getValue());
            } else {
                log.warn("坑位商品不可售或状态不可确认，跳过该坑位, position: {}, itemId: {}",
                        entry.getKey(), entry.getValue());
            }
        }
        return sellable;
    }

    /**
     * 判断商品是否可售
     *
     * @param itemId 商品标识
     * @return 可售返回 true；下架、售罄、缓存缺失或读取异常返回 false
     */
    private boolean isSellable(Long itemId) {
        if (itemId == null) {
            return false;
        }
        try {
            Map<Object, Object> state = recRedis.hgetAll(RecRedisKeys.itemState(itemId));
            if (state.isEmpty()) {
                // 缓存缺失属不可确认：保守跳过，避免下架商品借坑位绕过过滤
                return false;
            }
            String status = String.valueOf(state.get(RecRedisKeys.ITEM_STATE_FIELD_STATUS));
            String stock = String.valueOf(state.get(RecRedisKeys.ITEM_STATE_FIELD_STOCK));
            return RecRedisKeys.ITEM_STATUS_ON_SALE.equals(status) && !"0".equals(stock);
        } catch (Exception e) {
            log.warn("坑位商品可售状态读取失败，按不可售处理, itemId: {}", itemId, e);
            return false;
        }
    }

    /**
     * 读取当前生效的固定坑位（带短周期缓存）
     *
     * @return 位置到商品标识的映射；查询失败时沿用上一版缓存
     */
    private Map<Integer, Long> loadActiveSlots() {
        if (System.currentTimeMillis() - slotCacheAt < SLOT_CACHE_MILLIS) {
            return slotCache;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<RecFixedSlotDO> slots = recFixedSlotMapper.selectList(
                    new LambdaQueryWrapper<RecFixedSlotDO>()
                            .eq(RecFixedSlotDO::getStatus, SLOT_STATUS_ENABLED)
                            .le(RecFixedSlotDO::getStartTime, now)
                            .ge(RecFixedSlotDO::getEndTime, now)
                            .orderByAsc(RecFixedSlotDO::getSlotPosition));

            Map<Integer, Long> loaded = new LinkedHashMap<>();
            for (RecFixedSlotDO slot : slots) {
                // 同一位置存在多条启用记录时取位置靠前的一条，其余忽略（详细设计 3.7 异常分支）
                loaded.putIfAbsent(slot.getSlotPosition(), slot.getItemId());
            }
            slotCache = loaded;
            slotCacheAt = System.currentTimeMillis();
        } catch (Exception e) {
            // 坑位查询失败跳过注入，不影响榜单输出（详细设计 3.7 异常分支）
            log.warn("固定坑位查询失败，跳过注入", e);
            slotCacheAt = System.currentTimeMillis();
        }
        return slotCache;
    }

    /**
     * 打分异常时的兜底排序：按召回热度降序
     *
     * @param candidates 候选池
     * @return 榜单元素列表
     */
    private List<RankedItem> fallbackByHeat(List<FilteredCandidate> candidates) {
        List<FilteredCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparingDouble(FilteredCandidate::getNormalizedScore).reversed()
                .thenComparing(FilteredCandidate::getItemId));
        List<RankedItem> ranked = new ArrayList<>(sorted.size());
        for (FilteredCandidate candidate : sorted) {
            ranked.add(new RankedItem(candidate.getItemId(), primarySource(candidate),
                    candidate.getNormalizedScore(), false));
        }
        return ranked;
    }

    /**
     * 读取有序集合并转为映射
     *
     * @param key 键名
     * @return 成员到分值的映射；键不存在或读取失败返回空映射
     */
    private Map<Long, Double> readZset(String key) {
        try {
            List<ZSetOperations.TypedTuple<String>> tuples = recRedis.zrevrangeWithScores(key, 0, -1);
            Map<Long, Double> map = new LinkedHashMap<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() != null) {
                    map.put(parseLong(tuple.getValue()), tuple.getScore() == null ? 0D : tuple.getScore());
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("有序集合读取失败，按空处理, key: {}", key, e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * min-max 归一化，全池同分时返回 0.5
     *
     * @param value 原始值
     * @param min   池内最小值
     * @param max   池内最大值
     * @return 归一化值
     */
    private double minMax(double value, double min, double max) {
        double range = max - min;
        if (range == 0D) {
            return 0.5D;
        }
        return (value - min) / range;
    }

    /**
     * 读取打分权值
     *
     * <p>注意 0 是合法权值（品牌偏好默认 0，表示该因子不参与打分），因此不能用「0 视为缺失」判断；
     * 只有负数（配置异常）才回退到默认值。
     *
     * @param key          配置键
     * @param defaultValue 默认权值
     * @return 权值
     */
    private double resolveWeight(String key, double defaultValue) {
        double weight = configService.getDouble(key);
        return weight < 0D ? defaultValue : weight;
    }

    /**
     * 字符串转长整型，解析失败返回 0
     *
     * @param value 原始值
     * @return 数值
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 打分上下文：候选与三个因子的原始值、归一化值与总分
     */
    private static final class ScoreItem {

        private FilteredCandidate candidate;

        private double categoryRaw;

        private double heatRaw;

        private double brandRaw;

        private double normCategory;

        private double normHeat;

        private double normBrand;

        private double total;
    }
}
