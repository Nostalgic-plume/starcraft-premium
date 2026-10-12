package com.shop.constant;

/**
 * 订单模块常量
 * 集中管理订单状态、支付方式、缓存 Key 等魔数，避免散落在业务代码中
 *
 * @author shop
 */
public interface OrderConstants {

    /* ==================== 订单状态 ==================== */
    /** 待支付 */
    int STATUS_PENDING_PAY  = 0;
    /** 已支付 */
    int STATUS_PAID         = 1;
    /** 已发货 */
    int STATUS_DELIVERED    = 2;
    /** 已收货 */
    int STATUS_RECEIVED     = 3;
    /** 已完成 */
    int STATUS_COMPLETED    = 4;
    /** 已取消 */
    int STATUS_CANCELLED    = 5;
    /** 已退款 */
    int STATUS_REFUNDED     = 6;

    /* ==================== 支付方式 ==================== */
    /** 微信支付 */
    int PAY_WECHAT = 1;
    /** 支付宝 */
    int PAY_ALIPAY = 2;

    /* ==================== 超时配置 ==================== */
    /** 超时取消时间（分钟） */
    int PAY_TIMEOUT_MINUTES = 30;

    /* ==================== 缓存 Key 前缀 ==================== */
    /** 下单防重令牌 Redis Key 前缀 */
    String CACHE_ORDER_TOKEN = "order:token:";
    /** 接口预扣库存记录 Key 前缀（order:deduct:{orderToken}，兜底任务据此恢复悬空预扣） */
    String CACHE_ORDER_DEDUCT = "order:deduct:";

    /* ==================== 预扣兜底配置 ==================== */
    /** 预扣记录超时时间（分钟）：超过该时长仍未落单/未清理的预扣视为悬空，由定时任务恢复库存 */
    int DEDUCT_TIMEOUT_MINUTES = 60;

    /* ==================== 状态文字映射 ==================== */

    /**
     * 根据订单状态编码获取中文状态文本
     *
     * @param status 状态编码
     * @return 状态中文文本
     */
    static String getStatusText(Integer status) {
        return switch (status) {
            case STATUS_PENDING_PAY -> "待支付";
            case STATUS_PAID -> "已支付";
            case STATUS_DELIVERED -> "已发货";
            case STATUS_RECEIVED -> "已收货";
            case STATUS_COMPLETED -> "已完成";
            case STATUS_CANCELLED -> "已取消";
            case STATUS_REFUNDED -> "已退款";
            default -> "未知";
        };
    }
}
