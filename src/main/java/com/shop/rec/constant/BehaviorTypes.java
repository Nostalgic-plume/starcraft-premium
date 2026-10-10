package com.shop.rec.constant;

import java.util.Map;

/**
 * 行为类型常量与默认权值
 *
 * <p>
 * 权值默认值与《详细设计说明书》6.2 一致：浏览 1、加购 3、收藏 4、购买 8。
 * 计算侧（独立 Flink 作业）按设计约定以相同取值在自身工程内定义，两侧不做代码共享。
 *
 * @author shop
 */
public final class BehaviorTypes {

    private BehaviorTypes() {
    }

    /** 浏览（弱兴趣信号，量最大） */
    public static final String VIEW = "view";

    /** 加购（中等兴趣信号） */
    public static final String ADD_CART = "add_cart";

    /** 收藏（强兴趣信号） */
    public static final String FAVORITE = "favorite";

    /** 购买（最强兴趣信号） */
    public static final String PURCHASE = "purchase";

    /**
     * 初始化排除清单时需要写入的商品：购买与加购代表用户已作出决策
     */
    public static final Map<String, Boolean> EXCLUDE_EVENT_TYPES = Map.of(
            PURCHASE, Boolean.TRUE,
            ADD_CART, Boolean.TRUE
    );

    /**
     * 判断行为类型是否需要写入用户排除清单（已购 / 已加购）
     *
     * @param eventType 行为类型
     * @return 需要写入返回 true
     */
    public static boolean needExclude(String eventType) {
        return EXCLUDE_EVENT_TYPES.containsKey(eventType);
    }
}
