package com.shop.constant;

/**
 * 商品模块常量
 * 集中管理商品状态、缓存 Key 等魔数
 */
public interface ProductConstants {

    /* ==================== 商品状态 ==================== */
    /** 上架 */
    int STATUS_ON_SHELF  = 1;
    /** 下架 */
    int STATUS_OFF_SHELF = 2;
    /** 售罄 */
    int STATUS_SOLD_OUT  = 3;

    /* ==================== 缓存 Key 前缀 ==================== */
    /** 商品详情缓存 */
    String CACHE_PRODUCT     = "product:detail:";
    /** 分类列表缓存 */
    String CACHE_CATEGORY    = "category:list";
    /** SKU 库存缓存 */
    String CACHE_SKU_STOCK   = "sku:stock:";
}
