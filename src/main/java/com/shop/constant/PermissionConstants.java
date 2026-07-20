package com.shop.constant;

/**
 * 权限编码常量
 * 用于 @SaCheckPermission 注解
 */
public interface PermissionConstants {

    /* ==================== 商品 ==================== */
    /** 新增商品（商家 + 管理员） */
    String PRODUCT_ADD    = "product:add";
    /** 编辑商品（商家 + 管理员） */
    String PRODUCT_EDIT   = "product:edit";
    /** 删除商品（仅管理员） */
    String PRODUCT_DELETE = "product:delete";
    /** 上下架（商家 + 管理员） */
    String PRODUCT_STATUS = "product:status";

    /* ==================== 订单 ==================== */
    /** 查看所有订单（仅管理员） */
    String ORDER_ALL      = "order:all";

    /* ==================== 用户管理 ==================== */
    /** 用户管理（仅管理员） */
    String USER_MANAGE    = "user:manage";

    /* ==================== 数据统计 ==================== */
    /** 查看统计（仅管理员） */
    String STATS_VIEW     = "stats:view";
}
