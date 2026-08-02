package com.shop.service;

import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.vo.OrderVO;
import com.shop.mq.OrderCreateMessage;
import com.shop.common.PageResult;

import java.util.Map;

/**
 * 订单服务
 * 处理订单生命周期：防重令牌 → 创建（异步削峰）→ 支付回调 → 取消 → 确认收货
 * 新增管理员/商家维度的订单查询与管理
 */
public interface OrderService {

    /** 获取下单防重令牌 */
    String getOrderToken();

    /**
     * 异步创建订单（生产者侧）
     * <p>
     * 参数校验通过后将消息发到 RocketMQ，立即返回 processingId 供前端轮询，
     * 消费端异步完成 DB 写入（Redis 预扣 + MySQL 行锁 + 订单创建 + 快照 + 购物车标记）。
     * </p>
     *
     * @return processingId（前端轮询订单状态的凭证）
     */
    String createOrder(OrderCreateDTO dto);

    /**
     * 异步落单处理（消费端，带 @Transactional）
     * <p>
     * 由 OrderCreateConsumer 调用，在事务内完成 Redis 预扣库存 + MySQL 行锁扣库存 +
     * 订单创建 + 快照 + 购物车标记 + 淘汰缓存。
     * </p>
     */
    Long processOrder(OrderCreateMessage msg);

    /**
     * 查询异步落单处理状态（前端轮询接口）
     *
     * @return 包含 status（PROCESSING/订单ID/FAILED:原因/EXPIRED）的 Map
     */
    Map<String, Object> getProcessingStatus(String processingId);

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
