package com.shop.rec.item;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.rec.RecApplication;
import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.BehaviorTypes;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.mall.mapper.MallProductMapper;
import com.shop.rec.mall.model.MallProductDO;
import com.shop.rec.mapper.UserBehaviorLogMapper;
import com.shop.rec.mapper.UserProfileMapper;
import com.shop.rec.model.entity.UserBehaviorLogDO;
import com.shop.rec.model.entity.UserProfileDO;
import com.shop.rec.profile.UserProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-04 商品数据维护与 M-03 用户画像的集成测试
 *
 * <p>
 * 两个模块的定时任务 cron 位于凌晨低峰期，无法在测试中等待，故直接调用其入口方法。
 *
 * <p>
 * <b>前置条件</b>：MySQL（shop / shop_rec 库）、Redis 已启动，shop 库中存在可售商品。
 *
 * <p>
 * <b>必须显式激活 rec profile</b>：{@code RecApplication} 是在 {@code main()} 中调用
 * {@code setAdditionalProfiles("rec")} 的，而测试不经过 main，不会被自动激活——不激活时
 * 推荐服务侧的 {@code @Profile("rec")} 组件（含本测试依赖的商城库只读数据源）与
 * application-rec.yml 都不生效。同理，必须显式指定启动类，否则会被解析为商城后端上下文。
 *
 * @author shop
 */
@SpringBootTest(classes = RecApplication.class)
@ActiveProfiles("rec")
class ItemAndProfileIntegrationTest {

    /** 测试用用户标识：远离真实用户区间 */
    private static final long TEST_USER_ID = 888888L;

    @Autowired
    private ItemDataService itemDataService;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private MallProductMapper mallProductMapper;

    @Autowired
    private UserBehaviorLogMapper behaviorLogMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private RecRedis recRedis;

    /**
     * 商品全量加载：可售商品应写入状态缓存
     */
    @Test
    @DisplayName("M-04 全量加载：可售商品写入状态缓存")
    void fullReloadShouldCacheSellableItems() {
        itemDataService.fullReloadDaily();

        List<MallProductDO> sellable = mallProductMapper.selectSellableProducts(5);
        assertFalse(sellable.isEmpty(), "商城中应存在可售商品");

        for (MallProductDO product : sellable) {
            String stateKey = RecRedisKeys.itemState(product.getId());
            assertTrue(recRedis.exists(stateKey), "可售商品应有状态缓存: " + product.getId());
            String status = recRedis.hget(stateKey, RecRedisKeys.ITEM_STATE_FIELD_STATUS);
            assertEquals("1", status, "状态缓存中的上下架状态应为上架");
            // Flink 作业依赖该字段解析维度，必须存在
            assertTrue(recRedis.hget(stateKey, RecRedisKeys.ITEM_STATE_FIELD_CATEGORY) != null,
                    "状态缓存应包含类目标识（Flink 维度解析依赖）");
        }
    }

    /**
     * 用户画像生成：有足够行为的用户应生成有效版本画像
     */
    @Test
    @DisplayName("M-03 画像生成：有行为用户生成有效版本")
    void profileGenerationShouldCreateActiveVersion() {
        // 造数据：为该用户准备 5 条行为（超过冷启动阈值 3 条）
        List<MallProductDO> products = mallProductMapper.selectSellableProducts(1);
        assertFalse(products.isEmpty(), "需要一个真实商品用于造行为数据");
        Long itemId = products.get(0).getId();

        behaviorLogMapper.delete(new LambdaQueryWrapper<UserBehaviorLogDO>()
                .eq(UserBehaviorLogDO::getUserId, TEST_USER_ID));
        for (int i = 0; i < 5; i++) {
            UserBehaviorLogDO behaviorLog = new UserBehaviorLogDO();
            behaviorLog.setEventId("evt_profile_it_" + System.currentTimeMillis() + "_" + i);
            behaviorLog.setUserId(TEST_USER_ID);
            behaviorLog.setItemId(itemId);
            behaviorLog.setEventType(BehaviorTypes.VIEW);
            behaviorLog.setEventTime(LocalDateTime.now().minusHours(i));
            behaviorLog.setReceivedTime(LocalDateTime.now());
            behaviorLog.setSource(1);
            behaviorLogMapper.insert(behaviorLog);
        }

        userProfileService.generateDailyProfile();

        List<UserProfileDO> versions = userProfileMapper.selectVersionsByUserId(TEST_USER_ID);
        assertFalse(versions.isEmpty(), "有行为的用户应生成画像");
        assertEquals(0, versions.get(0).getStatus(), "最新版本应为有效状态");
        assertTrue(versions.get(0).getBehaviorCount() >= 5, "画像应记录参与归纳的行为条数");
        assertFalse(versions.get(0).getCategoryPref() == null
                        || versions.get(0).getCategoryPref().isEmpty(),
                "画像应至少包含一个类目偏好（商品维度可解析）");
    }
}
