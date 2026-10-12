package com.shop.rec.recall;

import com.shop.rec.RecApplication;
import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.enums.DegradeState;
import com.shop.rec.fusion.FusionFilterService;
import com.shop.rec.fusion.model.FilterResult;
import com.shop.rec.fusion.model.FilteredCandidate;
import com.shop.rec.item.ItemDataService;
import com.shop.rec.mall.mapper.MallProductMapper;
import com.shop.rec.mall.model.MallProductDO;
import com.shop.rec.rank.RankService;
import com.shop.rec.rank.model.RankResult;
import com.shop.rec.recall.model.RecallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回 → 融合过滤 → 重排 三段链路的集成测试（M-05 / M-06 / M-07）
 *
 * <p>
 * 覆盖 T5 的三个验收点：完整链路返回非空榜单、冷启动仅走热门召回、已购商品被过滤。
 *
 * <p>
 * <b>前置条件</b>：MySQL（shop / shop_rec）、Redis 已启动，shop 库中存在可售商品。
 * 测试在 {@code @BeforeEach} 中先执行一次商品全量加载，确保状态缓存与类目索引就绪；
 * 热度榜由 Flink 作业负责写入，测试中手工构造。
 *
 * @author shop
 */
@SpringBootTest(classes = RecApplication.class)
@ActiveProfiles("rec")
class RecallFusionRankIntegrationTest {

    /** 有实时特征的测试用户 */
    private static final long WARM_USER_ID = 777777L;

    /** 无特征无画像的冷启动用户 */
    private static final long COLD_USER_ID = 666666L;

    @Autowired
    private RecallService recallService;

    @Autowired
    private FusionFilterService fusionFilterService;

    @Autowired
    private RankService rankService;

    @Autowired
    private ItemDataService itemDataService;

    @Autowired
    private MallProductMapper mallProductMapper;

    @Autowired
    private RecRedis recRedis;

    /**
     * 准备基础数据：商品状态缓存、类目索引与热度榜
     */
    @BeforeEach
    void setUp() {
        itemDataService.fullReloadDaily();

        List<MallProductDO> products = mallProductMapper.selectSellableProducts(30);
        assertFalse(products.isEmpty(), "shop 库中应存在可售商品");

        // 热度榜由 Flink 作业写入，测试中手工构造：可售商品按序递减分值
        Map<String, Double> heatMembers = new HashMap<>();
        double heat = 100D;
        for (MallProductDO product : products) {
            heatMembers.put(String.valueOf(product.getId()), heat);
            heat = Math.max(1D, heat - 1D);
        }
        recRedis.zaddAll(RecRedisKeys.HOT_ITEM, heatMembers);
        recRedis.expire(RecRedisKeys.HOT_ITEM, 3600L);

        // 清理冷启动用户的画像与特征，保证其处于冷启动态
        recRedis.delete(RecRedisKeys.userCatPref(COLD_USER_ID));
        recRedis.delete(RecRedisKeys.userBrandPref(COLD_USER_ID));
    }

    /**
     * 完整链路：有特征用户应走双路召回并返回非空榜单
     */
    @Test
    @DisplayName("完整链路：有实时特征的用户返回非空个性化榜单")
    void fullPipelineShouldProduceNonEmptyRanking() {
        // 为一个可售商品的类目写入高偏好分，使类目路有输入
        MallProductDO product = mallProductMapper.selectSellableProducts(1).get(0);
        recRedis.zadd(RecRedisKeys.userCatPref(WARM_USER_ID), String.valueOf(product.getCategoryId()), 8D);
        recRedis.expire(RecRedisKeys.userCatPref(WARM_USER_ID), 3600L);

        RecallResult recall = recallService.recall(WARM_USER_ID);
        assertFalse(recall.isColdStart(), "有特征的用户不应判定为冷启动");
        assertTrue(recall.totalSize() > 0, "召回应产出候选");

        FilterResult filter = fusionFilterService.fuseAndFilter(recall, WARM_USER_ID);
        assertFalse(filter.getCandidates().isEmpty(), "过滤后候选池应非空");

        RankResult rank = rankService.rank(filter, WARM_USER_ID);
        assertNotNull(rank.getItems());
        assertFalse(rank.getItems().isEmpty(), "最终榜单应非空");
        assertEquals(filter.getCandidates().size(), rank.getItems().size(),
                "榜单条数应与候选池一致（无坑位配置时）");
    }

    /**
     * 冷启动：无特征无画像的用户仅走热门召回
     */
    @Test
    @DisplayName("冷启动：无特征无画像用户仅走热门召回")
    void coldStartShouldUseHotRecallOnly() {
        RecallResult recall = recallService.recall(COLD_USER_ID);

        assertTrue(recall.isColdStart(), "应判定为冷启动");
        assertEquals(DegradeState.COLD_START, recall.getDegradeState());
        assertTrue(recall.getCategoryCandidates().isEmpty(), "冷启动不应执行类目偏好召回");
        assertFalse(recall.getHotCandidates().isEmpty(), "冷启动应返回热门召回结果");
    }

    /**
     * 已购与已加购：命中排除清单的商品应被过滤掉
     */
    @Test
    @DisplayName("已购过滤：排除清单中的商品不出现在候选池")
    void recentlyPurchasedItemShouldBeFiltered() {
        MallProductDO product = mallProductMapper.selectSellableProducts(1).get(0);
        Long excludedItemId = product.getId();

        // 构造：该商品在热度榜中（能进候选），同时命中排除清单
        recRedis.sadd(RecRedisKeys.exclude(WARM_USER_ID), String.valueOf(excludedItemId));
        recRedis.expire(RecRedisKeys.exclude(WARM_USER_ID), 3600L);

        RecallResult recall = recallService.recall(WARM_USER_ID);
        FilterResult filter = fusionFilterService.fuseAndFilter(recall, WARM_USER_ID);

        boolean present = filter.getCandidates().stream()
                .map(FilteredCandidate::getItemId)
                .anyMatch(excludedItemId::equals);
        assertFalse(present, "已购商品不应出现在候选池中");
        assertTrue(filter.getFilterStats().getOrDefault("recentExcluded", 0) >= 1,
                "过滤统计应记录已购已加购的命中数");

        // 清理，避免影响其它测试
        recRedis.delete(RecRedisKeys.exclude(WARM_USER_ID));
    }
}
