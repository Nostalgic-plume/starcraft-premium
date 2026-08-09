package com.shop.constant;

/**
 * 商品模块常量
 * 集中管理商品状态、缓存 Key 等魔数
 */
public interface ProductConstants {

    /* ==================== 商品状态（与 product.status 列注释一致） ==================== */
    /** 待审核 */
    int STATUS_PENDING   = 0;
    /** 上架 */
    int STATUS_ON_SHELF  = 1;
    /** 下架 */
    int STATUS_OFF_SHELF = 2;

    /* ==================== 缓存 Key 前缀 ==================== */
    /** 商品详情缓存 */
    String CACHE_PRODUCT     = "product:detail:";
    /** 分类列表缓存 */
    String CACHE_CATEGORY    = "category:list";
    /** SKU 库存缓存 */
    String CACHE_SKU_STOCK   = "sku:stock:";

    /* ==================== 逻辑过期时长（防缓存击穿） ==================== */
    /** 商品详情逻辑过期时长（秒）：缓存物理永不过期，逻辑过期后返回旧数据 + 异步重建 */
    long CACHE_PRODUCT_LOGICAL_TTL = 30 * 60;
}
