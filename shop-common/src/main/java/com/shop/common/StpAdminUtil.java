package com.shop.common;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 管理后台端会话工具（loginType=admin）
 * <p>
 * 与用户端（StpUserUtil，loginType=user）、商家端（StpShopUtil，loginType=shop）
 * 使用独立 loginType，三端会话彻底隔离，杜绝 loginId 冲突导致的身份串用。
 * </p>
 *
 * @author shop
 */
public class StpAdminUtil extends StpLogic {

    private StpAdminUtil() {
        super("admin");
    }

    /** 全局单例：构造时自动注册到 SaManager */
    public static final StpAdminUtil INSTANCE = new StpAdminUtil();
}
