package com.shop.rec.recall;

import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.enums.DegradeState;
import com.shop.rec.item.ItemDataService;
import com.shop.rec.mapper.UserProfileMapper;
import com.shop.rec.model.entity.UserProfileDO;
import com.shop.rec.model.vo.PrefItemVO;
import com.shop.rec.recall.model.RecallCandidate;
import com.shop.rec.recall.model.RecallResult;
import com.shop.rec.common.RecRedis;
import com.shop.rec.service.RecConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 双路召回（M-05）
 *
 * <p>
 * 严格对应《详细设计说明书》3.5：并行执行类目偏好召回与热门召回，按配置比例产出两路候选，
 * 并对单路结果做有效性校验，实现单路失败隔离。
 *
 * <p>
 * <b>冷启动判定</b>：按详细设计 6.6 的判定表分三种走向——
 * <ul>
 *     <li>无实时特征且无画像 → 冷启动，仅执行热门召回；</li>
 *     <li>无实时特征但有画像 → 特征缺失，以画像类目作为类目路输入，同时执行热门召回；</li>
 *     <li>有实时特征 → 正常，执行完整双路召回。</li>
 * </ul>
 * 游客与鉴权由 M-08 在更外层处理，不进入本服务。
 *
 * <p>
 * <b>单路隔离</b>：类目偏好路异常或结果不足即丢弃该路，配额并入热门路，标记「召回降级」；
 * 热门路异常则改用兜底名单（热度榜 TopN 的进程内副本），标记「兜底名单」。
 * 两路都不可用时仍返回兜底名单，保证候选池非空。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class RecallService {

    /** 单路有效性下限：候选条数需达到该路配额的该比例，否则视为该路不可用 */
    private static final double VALID_RATIO = 0.5D;

    /** 召回比例配置的分隔符，形如「70:30」 */
    private static final String RATIO_SEPARATOR = ":";

    /** 默认类目路占比，配置缺失时兜底 */
    private static final int DEFAULT_CATEGORY_RATIO = 70;

    private final RecRedis recRedis;

    private final RecConfigService configService;

    private final UserProfileMapper userProfileMapper;

    private final ItemDataService itemDataService;

    /**
     * 执行双路召回
     *
     * @param userId 用户标识
     * @return 两路候选与命中的降级状态
     */
    public RecallResult recall(Long userId) {
        RecallResult result = new RecallResult();
        int poolSize = resolvePoolSize();
        int[] quotas = calculateQuotas(poolSize);

        // 步骤 1~2：读取实时特征并按 6.6 判定表确定走向
        List<PrefScoreHolder> categoryPrefs = readCategoryPrefs(userId);
        if (categoryPrefs.isEmpty()) {
            List<PrefScoreHolder> profilePrefs = loadProfileCategories(userId);
            if (profilePrefs.isEmpty()) {
                // 冷启动：无实时特征且无画像，仅执行热门召回
                result.setColdStart(true);
                result.setDegradeState(DegradeState.COLD_START);
                result.setHotCandidates(hotRecall(quotas[1]));
                return result;
            }
            categoryPrefs = profilePrefs;
            result.setDegradeState(DegradeState.FEATURE_MISSING);
        }

        // 步骤 3~4：两路召回（同机内存操作，顺序执行即可实现物理并行；任一路异常互不影响）
        List<RecallCandidate> categoryCandidates = safeCategoryRecall(categoryPrefs, quotas[0]);
        List<RecallCandidate> hotCandidates = safeHotRecall(quotas[1]);

        // 步骤 7：单路有效性校验，不可用即降级
        if (categoryCandidates.size() < quotas[0] * VALID_RATIO) {
            log.info("类目偏好路候选不足（{} < {}），丢弃该路, userId: {}",
                    categoryCandidates.size(), (int) (quotas[0] * VALID_RATIO), userId);
            categoryCandidates = Collections.emptyList();
            result.setDegradeState(DegradeState.RECALL_DEGRADED);
        }
        if (hotCandidates.size() < quotas[1] * VALID_RATIO) {
            log.info("热门路候选不足（{} < {}），改用兜底名单, userId: {}",
                    hotCandidates.size(), (int) (quotas[1] * VALID_RATIO), userId);
            hotCandidates = fallbackRecall(quotas[1]);
            result.setDegradeState(DegradeState.FALLBACK_LIST);
        }

        result.setCategoryCandidates(categoryCandidates);
        result.setHotCandidates(hotCandidates);
        return result;
    }

    /**
     * 读取用户类目偏好特征
     *
     * <p>超时或异常按无特征处理，不向调用方抛异常（详细设计 3.5 步骤 1 的失败语义）。
     *
     * @param userId 用户标识
     * @return 类目偏好（按分值降序）；不可用时返回空列表
     */
    private List<PrefScoreHolder> readCategoryPrefs(Long userId) {
        try {
            List<ZSetOperations.TypedTuple<String>> tuples =
                    recRedis.zrevrangeWithScores(RecRedisKeys.userCatPref(userId), 0, -1);
            List<PrefScoreHolder> prefs = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() != null && tuple.getScore() != null) {
                    prefs.add(new PrefScoreHolder(parseLong(tuple.getValue()), tuple.getScore()));
                }
            }
            return prefs;
        } catch (Exception e) {
            log.warn("类目偏好特征读取失败，按无特征处理, userId: {}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 以画像类目作为类目召回输入（实时特征缺失时的兜底）
     *
     * @param userId 用户标识
     * @return 画像中的类目偏好；无有效画像时返回空列表
     */
    private List<PrefScoreHolder> loadProfileCategories(Long userId) {
        try {
            for (UserProfileDO version : userProfileMapper.selectVersionsByUserId(userId)) {
                if (version.getStatus() != null && version.getStatus() == 0
                        && version.getCategoryPref() != null && !version.getCategoryPref().isEmpty()) {
                    List<PrefScoreHolder> prefs = new ArrayList<>(version.getCategoryPref().size());
                    for (PrefItemVO pref : version.getCategoryPref()) {
                        prefs.add(new PrefScoreHolder(pref.getDimId(),
                                pref.getScore() == null ? 0D : pref.getScore().doubleValue()));
                    }
                    return prefs;
                }
            }
        } catch (Exception e) {
            log.warn("画像类目读取失败, userId: {}", userId, e);
        }
        return Collections.emptyList();
    }

    /**
     * 类目偏好召回（带异常隔离）
     *
     * @param categoryPrefs 类目偏好（按分值降序）
     * @param quota         该路配额
     * @return 候选列表；异常时返回空列表
     */
    private List<RecallCandidate> safeCategoryRecall(List<PrefScoreHolder> categoryPrefs, int quota) {
        try {
            return categoryRecall(categoryPrefs, quota);
        } catch (Exception e) {
            log.warn("类目偏好召回异常，丢弃该路", e);
            return Collections.emptyList();
        }
    }

    /**
     * 类目偏好召回
     *
     * <p>取偏好分最高的前 N 个类目，逐类目读取类目候选索引并取前 M 名，按该顺序拼接后
     * 截取到配额（详细设计 3.5 步骤 3）。
     *
     * @param categoryPrefs 类目偏好（按分值降序）
     * @param quota         该路配额
     * @return 候选列表
     */
    private List<RecallCandidate> categoryRecall(List<PrefScoreHolder> categoryPrefs, int quota) {
        int topN = Math.max(1, configService.getInt(RecConfigKeys.RECALL_CATEGORY_TOPN));
        int perCategoryLimit = Math.max(1, configService.getInt(RecConfigKeys.RECALL_CATEGORY_LIMIT));

        List<RecallCandidate> candidates = new ArrayList<>();
        int usedCategories = 0;
        for (PrefScoreHolder pref : categoryPrefs) {
            if (usedCategories >= topN || candidates.size() >= quota) {
                break;
            }
            List<ZSetOperations.TypedTuple<String>> items = recRedis.zrevrangeWithScores(
                    RecRedisKeys.catItems(pref.dimValue), 0, perCategoryLimit - 1L);
            for (ZSetOperations.TypedTuple<String> item : items) {
                if (item.getValue() == null) {
                    continue;
                }
                candidates.add(new RecallCandidate(parseLong(item.getValue()),
                        RecallCandidate.SOURCE_CATEGORY_PREF,
                        item.getScore() == null ? 0D : item.getScore(),
                        pref.dimValue));
            }
            usedCategories++;
        }
        return candidates.size() > quota ? new ArrayList<>(candidates.subList(0, quota)) : candidates;
    }

    /**
     * 热门召回（带异常隔离）
     *
     * @param quota 该路配额
     * @return 候选列表；异常时返回空列表
     */
    private List<RecallCandidate> safeHotRecall(int quota) {
        try {
            return hotRecall(quota);
        } catch (Exception e) {
            log.warn("热门召回异常，该路将改用兜底名单", e);
            return Collections.emptyList();
        }
    }

    /**
     * 热门召回
     *
     * <p>读取商品热度榜前 N 名（详细设计 3.5 步骤 4）。
     *
     * @param quota 该路配额
     * @return 候选列表
     */
    private List<RecallCandidate> hotRecall(int quota) {
        List<ZSetOperations.TypedTuple<String>> items =
                recRedis.zrevrangeWithScores(RecRedisKeys.HOT_ITEM, 0, quota - 1L);
        List<RecallCandidate> candidates = new ArrayList<>(items.size());
        for (ZSetOperations.TypedTuple<String> item : items) {
            if (item.getValue() == null) {
                continue;
            }
            candidates.add(new RecallCandidate(parseLong(item.getValue()),
                    RecallCandidate.SOURCE_HOT,
                    item.getScore() == null ? 0D : item.getScore(),
                    null));
        }
        return candidates;
    }

    /**
     * 兜底召回：使用热度榜 TopN 的进程内副本
     *
     * @param quota 该路配额
     * @return 候选列表；兜底名单为空时返回空列表
     */
    private List<RecallCandidate> fallbackRecall(int quota) {
        List<Long> fallbackItems = itemDataService.getFallbackItems();
        if (fallbackItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecallCandidate> candidates = new ArrayList<>();
        for (Long itemId : fallbackItems) {
            if (candidates.size() >= quota) {
                break;
            }
            candidates.add(new RecallCandidate(itemId, RecallCandidate.SOURCE_FALLBACK, 0D, null));
        }
        return candidates;
    }

    /**
     * 解析候选池规模
     *
     * @return 候选池目标规模；配置异常时取 200
     */
    private int resolvePoolSize() {
        int poolSize = configService.getInt(RecConfigKeys.RECALL_POOL_SIZE);
        return poolSize > 0 ? poolSize : 200;
    }

    /**
     * 按详细设计 6.3 的公式计算两路配额
     *
     * <p>{@code N_cat = round(N × r_cat / 100)}、{@code N_hot = N − N_cat}。
     *
     * @param poolSize 候选池目标规模
     * @return 数组 [类目路配额, 热门路配额]
     */
    private int[] calculateQuotas(int poolSize) {
        String ratio = configService.getString(RecConfigKeys.RECALL_RATIO);
        int categoryRatio = DEFAULT_CATEGORY_RATIO;
        if (ratio != null && ratio.contains(RATIO_SEPARATOR)) {
            String[] parts = ratio.split(RATIO_SEPARATOR);
            try {
                categoryRatio = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException e) {
                categoryRatio = DEFAULT_CATEGORY_RATIO;
            }
        }
        int categoryQuota = (int) Math.round(poolSize * categoryRatio / 100.0D);
        return new int[]{categoryQuota, poolSize - categoryQuota};
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
     * 偏好分值载体
     *
     * <p>本服务内部使用，避免与 Flink 工程的 PrefScore 产生跨工程依赖。
     */
    private static final class PrefScoreHolder {

        private final long dimValue;

        private final double score;

        private PrefScoreHolder(long dimValue, double score) {
            this.dimValue = dimValue;
            this.score = score;
        }
    }
}
