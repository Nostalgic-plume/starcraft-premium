package com.shop.service;

import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.vo.OrderVO;
import com.shop.common.PageResult;

/**
 * 订单服务
 * 处理订单生命周期：防重令牌 → 创建 → 支付回调 → 取消 → 确认收货
 * 新增管理员/商家维度的订单查询与管理
 */
public interface OrderService {

    /** 获取下单防重令牌 */
    String getOrderToken();

    /** 创建订单（含 shop_id 绑定、购物车 is_ordered 标记） */
    Long createOrder(OrderCreateDTO dto);

    /** 支付回调处理（幂等） */
    void handlePaymentCallback(String orderNo, Integer payType);

    /** 取消订单 */
    void cancelOrder(Long orderId);

    /** 确认收货 */
    void confirmReceive(Long orderId);

    /** 用户端订单详情 */
    OrderVO getDetail(Long orderId);

    /** 用户端我的订单列表 */
    PageResult<OrderVO> myOrders(Integer page, Integer pageSize, Integer status);

    // ==================== 管理员端 ====================

    /**
     * 管理员订单分页（全平台数据，多条件筛选）
     *
     * @param page      页码
     * @param pageSize  每页条数
     * @param status    订单状态
     * @param shopId    商家ID（可选）
     * @param startTime 开始时间（可选）
     * @param endTime   结束时间（可选）
     * @param keyword   关键字（订单号/收货人）
     * @return 分页结果
     */
    PageResult<OrderVO> adminPageOrders(int page, int pageSize, Integer status,
                                        Long shopId, String startTime, String endTime, String keyword);

    /** 管理员端订单详情（无归属校验，全平台可查） */
    OrderVO adminGetDetail(Long orderId);

    // ==================== 商家端 ====================

    /**
     * 商家订单分页（强制 shop_id 过滤，shop_id 从 StpUtil 自动获取）
     *
     * @param page     页码
     * @param pageSize 每页条数
     * @param status   订单状态
     * @return 分页结果
     */
    PageResult<OrderVO> merchantPageOrders(int page, int pageSize, Integer status);

    /** 商家端订单详情（shop_id 归属校验，shop_id 从 StpUtil 自动获取） */
    OrderVO merchantGetDetail(Long orderId);

    /**
     * 商家发货（shop_id 从 StpUtil 自动获取）
     *
     * @param orderId    订单ID
     * @param logisticsNo 物流单号
     */
    void merchantDeliver(Long orderId, String logisticsNo);
}
