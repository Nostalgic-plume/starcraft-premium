package com.shop.rec.flink.constant;

import java.util.Map;

/**
 * 行为权值与窗口权值默认值
 *
 * <p>
 * 对应《详细设计说明书》6.2（行为加权）与 6.1（偏好分合成）。
 * 权值可经后台配置调整，作业侧以本类默认值为准（配置热更新对计算侧的影响见详细设计 3.2 输入说明）。
 *
 * <p>
 * <b>与推荐服务的共享约定</b>：行为权值默认值与推荐服务侧 {@code BehaviorTypes} 属同一约定，
 * 两侧以常量各自定义、不做代码共享。
 *
 * @author shop
 */
public final class BehaviorWeights {

    private BehaviorWeights() {
    }

    /** 行为类型：浏览 */
    public static final String VIEW = "view";

    /** 行为类型：加购 */
    public static final String ADD_CART = "add_cart";

    /** 行为类型：收藏 */
    public static final String FAVORITE = "favorite";

    /** 行为类型：购买 */
    public static final String PURCHASE = "purchase";

    /**
     * 行为类型到分值的映射（默认值）
     *
     * <p>浏览为弱兴趣信号但量最大，购买为最强兴趣信号；
     * 时长字段 duration 不参与加权，见详细设计 6.2 的排除约定。
     */
    public static final Map<String, Double> BEHAVIOR_SCORES = Map.of(
            VIEW, 1D,
            ADD_CART, 3D,
            FAVORITE, 4D,
            PURCHASE, 8D
    );

    // ==================== 六档窗口权值（合计必须为 1.00） ====================

    /** 1 分钟窗口权值 */
    public static final double WINDOW_1MIN = 0.30D;

    /** 5 分钟窗口权值 */
    public static final double WINDOW_5MIN = 0.20D;

    /** 10 分钟窗口权值 */
    public static final double WINDOW_10MIN = 0.15D;

    /** 1 小时窗口权值 */
    public static final double WINDOW_1H = 0.15D;

    /** 24 小时窗口权值 */
    public static final double WINDOW_24H = 0.15D;

    /** 7 天窗口权值 */
    public static final double WINDOW_7D = 0.05D;

    /**
     * 查询行为类型对应的分值
     *
     * @param eventType 行为类型
     * @return 分值；未知类型返回 0（不参与累加）
     */
    public static double scoreOf(String eventType) {
        Double score = BEHAVIOR_SCORES.get(eventType);
        return score == null ? 0D : score;
    }
}
