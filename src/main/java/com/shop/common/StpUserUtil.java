package com.shop.common;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 用户端会话工具（loginType=user）
 * <p>
 * 与商家端（StpShopUtil，loginType=shop）、管理后台端（StpAdminUtil，loginType=admin）
 * 使用独立 loginType，Sa-Token 的 session key 形如 {@code token:{loginType}:session:{loginId}}，
 * 三端会话彻底隔离，杜绝 loginId 冲突（如 admin.id 与 shop.id 同为 1）导致的身份串用。
 * </p>
 *
 * @author shop
 */
public class StpUserUtil extends StpLogic {

    private StpUserUtil() {
        super("user");
    }

    /** 全局单例：构造时自动注册到 SaManager，供各 Service/Controller 直接调用 */
    public static final StpUserUtil INSTANCE = new StpUserUtil();
}
