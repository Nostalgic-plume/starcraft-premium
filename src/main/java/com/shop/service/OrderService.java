package com.shop.service;

import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.vo.OrderVO;
import com.shop.mq.OrderCreateMessage;
import com.shop.common.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 订单服务
 * 处理订单生命周期：防重令牌 → 创建（异步削峰）→ 支付回调 → 取消 → 确认收货
 * 新增管理员/商家维度的订单查询与管理
 *
 * @author shop
 */
public interface OrderService {

    /**
     * 获取下单防重令牌
     *
     * @return 防重令牌字符串（Redis 防重复提交校验，重复使用会报错）
     */
    String getOrderToken();

    /**
     * 异步创建订单（生产者侧）
     * <p>
     * 参数校验通过后将消息发到 RocketMQ，立即返回 processingId 供前端轮询，
     * 消费端异步完成 DB 写入（Redis 预扣 + MySQL 行锁 + 订单创建 + 快照 + 购物车标记）。
     * </p>
     *
     * @param dto 下单参数（含防重令牌、选中的购物车 SKU 明细、收货信息）
     * @return processingId（前端轮询订单状态的凭证）
     * @throws BusinessException 重复提交、商品不存在/已下架、库存不足或 MQ 发送失败时抛出
     */
    String createOrder(OrderCreateDTO dto);

    /**
     * 异步落单处理（消费端，带 @Transactional）
     * <p>
     * 由 OrderCreateConsumer 调用，在事务内完成 Redis 预扣库存 + MySQL 行锁扣库存 +
     * 订单创建 + 快照 + 购物车标记 + 淘汰缓存。
     * </p>
     *
     * @param msg 落单消息（含用户 ID、SKU 明细、收货信息）
     * @return 新创建的订单 ID
     * @throws BusinessException 商品不存在/已下架、库存不足或混入多家商家商品时抛出
     */
    Long processOrder(OrderCreateMessage msg);

    /**
     * 查询异步落单处理状态（前端轮询接口）
     *
     * @param processingId createOrder 返回的异步处理凭证
     * @return 包含 status（PROCESSING/订单ID/FAILED:原因/EXPIRED）的 Map
     */
    Map<String, Object> getProcessingStatus(String processingId);

    /**
     * 支付回调处理（幂等）
     * 仅将待支付订单更新为已支付，重复回调或状态非法时静默忽略
     *
     * @param orderNo 订单号
     * @param payType 支付方式
     */
    void handlePaymentCallback(String orderNo, Integer payType);

    /**
     * 取消订单（仅待支付状态可取消）
     * 取消后回滚 Redis 与 MySQL 库存
     *
     * @param orderId 订单ID
     * @throws BusinessException 订单不存在、无权操作或当前状态不允许取消时抛出
     */
    void cancelOrder(Long orderId);

    /**
     * 按指定 userId 取消订单（AI 客服工具调用用）
     * agent loop 在虚拟线程异步执行，StpUtil 的 ThreadLocal 登录态不可用，故 userId 显式传入；
     * 幂等（状态机仅待支付可取消）+ 库存回滚逻辑与 cancelOrder 一致
     *
     * @param orderId 订单ID
     * @param userId  用户ID
     * @throws BusinessException 订单不存在、非本人订单或状态不允许取消时抛出
     */
    void cancelOrderByUserId(Long orderId, Long userId);

    /**
     * 确认收货（仅已发货状态可确认）
     *
     * @param orderId 订单ID
     * @throws BusinessException 订单不存在、无权操作或当前状态不允许确认收货时抛出
     */
    void confirmReceive(Long orderId);

    /**
     * 用户端订单详情（归属校验：仅本人可查看）
     *
     * @param orderId 订单ID
     * @return 订单详情 VO（含商品快照）
     * @throws BusinessException 订单不存在或无权限查看时抛出
     */
    OrderVO getDetail(Long orderId);

    /**
     * 用户端我的订单列表（分页 + 状态筛选）
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     * @param status   订单状态（null=全部）
     * @return 分页订单结果
     */
    PageResult<OrderVO> myOrders(Integer page, Integer pageSize, Integer status);

    // ==================== AI 客服工具调用（显式 userId，不依赖 StpUtil） ====================

    /**
     * 按指定 userId 查询订单列表（AI 客服工具调用用）
     * agent loop 在虚拟线程异步执行，StpUtil 的 ThreadLocal 登录态不可用，故显式传 userId
     *
     * @param userId 用户ID
     * @param status 订单状态（null=全部）
     * @param limit  返回条数上限
     * @return 订单列表 VO（含商品快照与状态文案）
     */
    List<OrderVO> myOrdersByUserId(Long userId, Integer status, int limit);

    /**
     * 按指定 userId 查询订单详情（AI 客服工具调用用，内置归属校验）
     *
     * @param orderId 订单ID
     * @param userId  用户ID
     * @return 订单详情 VO
     * @throws BusinessException 订单不存在或非本人订单时抛出
     */
    OrderVO getOrderDetailByUserId(Long orderId, Long userId);

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

    /**
     * 管理员端订单详情（无归属校验，全平台可查）
     *
     * @param orderId 订单ID
     * @return 订单详情 VO
     * @throws BusinessException 订单不存在时抛出
     */
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

    /**
     * 商家端订单详情（shop_id 归属校验，shop_id 从 StpUtil 自动获取）
     *
     * @param orderId 订单ID
     * @return 订单详情 VO
     * @throws BusinessException 订单不存在或非本店订单时抛出
     */
    OrderVO merchantGetDetail(Long orderId);

    /**
     * 商家发货（shop_id 从 StpUtil 自动获取）
     *
     * @param orderId    订单ID
     * @param logisticsNo 物流单号
     * @throws BusinessException 订单不存在、非本店订单或订单非已支付状态时抛出
     */
    void merchantDeliver(Long orderId, String logisticsNo);
}
