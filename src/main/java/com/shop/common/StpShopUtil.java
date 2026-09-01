package com.shop.common;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 商家端会话工具（loginType=shop）
 * <p>
 * 与用户端（StpUserUtil，loginType=user）、管理后台端（StpAdminUtil，loginType=admin）
 * 使用独立 loginType，三端会话彻底隔离。数据隔离拦截器 {@code ShopDataIsolationInterceptor}
 * 通过 {@code StpShopUtil.INSTANCE.isLogin()} 判断商家会话。
 * </p>
 *
 * @author shop
 */
public class StpShopUtil extends StpLogic {

    private StpShopUtil() {
        super("shop");
    }

    /** 全局单例：构造时自动注册到 SaManager */
    public static final StpShopUtil INSTANCE = new StpShopUtil();
}
