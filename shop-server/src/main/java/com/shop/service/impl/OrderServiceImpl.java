package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.common.StpShopUtil;
import com.shop.common.StpUserUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.common.PaginationHelper;
import com.shop.constant.OrderConstants;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CartMapper;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
import com.shop.mq.OrderCreateMessage;
import com.shop.mq.OrderProducer;
import com.shop.service.StockCacheService;
import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.entity.CartDO;
import com.shop.model.entity.OrderDO;
import com.shop.model.entity.OrderItemDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.entity.SkuDO;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 * 覆盖完整订单生命周期：防重令牌 → 创建（库存行锁+shop_id绑定+购物车标记）→ 支付回调（幂等）→ 取消（库存回滚）→ 确认收货
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final CartMapper cartMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final StockCacheService stockCacheService;
    private final OrderProducer orderProducer;
    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    /**
     * 生成下单防重令牌：随机 UUID 存入 Redis（key 绑定当前用户）有效期 5 分钟，创建订单时校验并消耗
     *
     * @return 防重令牌字符串
     */
    @Override
    public String getOrderToken() {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                OrderConstants.CACHE_ORDER_TOKEN + userId,
                token, 5, TimeUnit.MINUTES);
        return token;
    }

    /**
     * 创建订单（异步削峰 + 接口预扣）：先校验并消耗防重令牌，再对每个 SKU 做存在性/上架状态/库存初判，
     * 然后 Redis Lua 原子预扣库存（快速失败：库存不足在接口即拒绝，不投无效消息进队列）；
     * 预扣成功写预扣记录、设置 PROCESSING 轮询状态后投递 RocketMQ，立即返回处理标识供前端轮询；
     * 发送失败时恢复已预扣库存并清理预扣记录与轮询状态
     *
     * @param dto 下单参数（items 商品项列表、收件人姓名/电话/地址、备注，均必填）
     * @return 处理标识 orderToken（同时作为前端轮询的 processingId）
     * @throws BusinessException 请勿重复提交订单、未选择商品、商品不存在、商品已下架、库存不足、Redis 异常或 MQ 发送失败（系统繁忙）
     */
    @Override
    public String createOrder(OrderCreateDTO dto) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();

        // ① Token 防重校验
        Boolean deleted = redisTemplate.delete(OrderConstants.CACHE_ORDER_TOKEN + userId);
        if (deleted == null || !deleted) {
            throw new BusinessException("请勿重复提交订单");
        }

        // ② 参数校验（SKU 存在 + 商品上架 + 库存初判）：早拒绝无效请求
        List<OrderCreateDTO.OrderItemDTO> items = dto.getItems();
        if (CollUtil.isEmpty(items)) {
            throw new BusinessException("请选择商品");
        }

        List<OrderCreateMessage.ItemDTO> msgItems = new ArrayList<>();
        Map<Long, String> skuNameMap = new HashMap<>();
        for (OrderCreateDTO.OrderItemDTO item : items) {
            SkuDO sku = skuMapper.selectById(item.getSkuId());
            if (sku == null) {
                throw new BusinessException("商品不存在，skuId: " + item.getSkuId());
            }
            ProductDO product = productMapper.selectById(sku.getProductId());
            if (product == null || !Integer.valueOf(1).equals(product.getStatus())) {
                throw new BusinessException("商品已下架，" + sku.getName());
            }
            if (item.getQuantity() > sku.getStock()) {
                throw new BusinessException("库存不足，" + sku.getName());
            }
            skuNameMap.put(item.getSkuId(), sku.getName());
            OrderCreateMessage.ItemDTO msgItem = new OrderCreateMessage.ItemDTO();
            msgItem.setSkuId(item.getSkuId());
            msgItem.setQuantity(item.getQuantity());
            msgItems.add(msgItem);
        }

        // ③ Redis 原子预扣库存（快速失败：秒杀场景库存不足在接口即拒绝，不投无效消息进队列）
        // 预扣成功后才发 MQ；库存不足/Redis 异常时回滚已预扣，消费端失败时的恢复见 processOrder 事务同步
        List<Long> deductSkuIds = new ArrayList<>();
        List<Integer> deductQtys = new ArrayList<>();
        for (OrderCreateDTO.OrderItemDTO item : items) {
            boolean ok;
            try {
                ok = stockCacheService.tryDeduct(item.getSkuId(), item.getQuantity());
            } catch (Exception e) {
                // Redis 异常：回滚已预扣，接口降级为失败（DB 尚未扣减，用户可重试）
                for (int j = 0; j < deductSkuIds.size(); j++) {
                    stockCacheService.restoreStock(deductSkuIds.get(j), deductQtys.get(j));
                }
                log.error("Redis 预扣库存异常: skuId={}", item.getSkuId(), e);
                throw new BusinessException("系统繁忙，请稍后重试");
            }
            if (!ok) {
                // 库存不足：回滚已预扣，快速失败返回
                for (int j = 0; j < deductSkuIds.size(); j++) {
                    stockCacheService.restoreStock(deductSkuIds.get(j), deductQtys.get(j));
                }
                throw new BusinessException("库存不足，" + skuNameMap.get(item.getSkuId()));
            }
            deductSkuIds.add(item.getSkuId());
            deductQtys.add(item.getQuantity());
        }

        // ④ 构建消息体（orderToken 同时也是 processingId，供前端轮询）
        String orderToken = UUID.randomUUID().toString().replace("-", "");
        OrderCreateMessage msg = new OrderCreateMessage();
        msg.setOrderToken(orderToken);
        msg.setUserId(userId);
        msg.setItems(msgItems);
        msg.setReceiverName(dto.getReceiverName());
        msg.setReceiverPhone(dto.getReceiverPhone());
        msg.setReceiverAddress(dto.getReceiverAddress());
        msg.setRemark(dto.getRemark());

        // ⑤ 写预扣记录（供兜底任务恢复悬空预扣）+ 设置 PROCESSING 状态（前端轮询用）
        // 预扣记录格式：{毫秒时间戳},{skuId}:{qty},{skuId}:{qty}...，正常落单/业务失败时由消费端删除
        String deductKey = OrderConstants.CACHE_ORDER_DEDUCT + orderToken;
        StringBuilder deductDetail = new StringBuilder().append(System.currentTimeMillis());
        for (int i = 0; i < deductSkuIds.size(); i++) {
            deductDetail.append(',').append(deductSkuIds.get(i)).append(':').append(deductQtys.get(i));
        }
        redisTemplate.opsForValue().set(deductKey, deductDetail.toString());
        redisTemplate.opsForValue().set(
                "order:process:" + orderToken,
                "PROCESSING",
                5, TimeUnit.MINUTES);

        // ⑥ 发送 MQ（失败则恢复预扣库存并清理状态）
        try {
            orderProducer.sendOrderCreate(msg);
        } catch (Exception e) {
            for (int i = 0; i < deductSkuIds.size(); i++) {
                stockCacheService.restoreStock(deductSkuIds.get(i), deductQtys.get(i));
            }
            redisTemplate.delete(deductKey);
            redisTemplate.delete("order:process:" + orderToken);
            log.error("MQ 发送失败，已恢复预扣库存: userId={}", userId, e);
            throw new BusinessException("系统繁忙，请稍后重试");
        }

        log.info("异步落单消息已投递: orderToken={}, userId={}", orderToken, userId);
        return orderToken;
    }

    /**
     * 消费端异步落单（事务内）：库存已由接口层 Redis 预扣，此处重新查询 SKU/商品最新状态 → 校验单一商家 shop_id
     * → MySQL 行锁扣库存（最终凭证）→ 创建订单与商品快照 → 标记购物车已下单 → 写订单缓存并淘汰商品缓存。
     * 事务同步策略：落单成功删除接口预扣记录；业务失败（不重试）恢复接口预扣的 Redis 库存；系统异常（会重试）
     * 保留预扣，保证重试成功后 Redis 缓存与 DB 一致
     *
     * @param msg RocketMQ 落单消息（userId、items、收件人信息等）
     * @return 新创建的订单ID
     * @throws BusinessException 商品不存在、商品已下架、库存不足、订单中包含多个商家的商品、商品所属商家信息缺失
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long processOrder(OrderCreateMessage msg) {
        long userId = msg.getUserId();
        List<OrderCreateMessage.ItemDTO> items = msg.getItems();
        final String orderToken = msg.getOrderToken();

        // ① 注册事务同步：提交删预扣记录；业务失败（不重试）恢复接口预扣库存；系统异常保留预扣等 MQ 重试
        final boolean[] businessFailed = {false};
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 事务结束后回调：
             * 提交 → 接口预扣的 Redis 库存已由 DB 行锁真实扣减，删除预扣记录；
             * 业务失败回滚（不重试） → 恢复接口已预扣的 Redis 库存并删除预扣记录；
             * 系统异常回滚（会重试） → 保留预扣与预扣记录，重试成功时缓存与 DB 保持一致
             *
             * @param status 事务完成状态（STATUS_ROLLED_BACK=回滚，STATUS_COMMITTED=已提交）
             */
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    redisTemplate.delete(OrderConstants.CACHE_ORDER_DEDUCT + orderToken);
                } else if (status == STATUS_ROLLED_BACK && businessFailed[0]) {
                    for (OrderCreateMessage.ItemDTO item : msg.getItems()) {
                        stockCacheService.restoreStock(item.getSkuId(), item.getQuantity());
                    }
                    redisTemplate.delete(OrderConstants.CACHE_ORDER_DEDUCT + orderToken);
                    log.info("异步落单业务失败，已恢复接口预扣的 Redis 库存: orderToken={}", orderToken);
                }
            }
        });

        try {
            // ② 重新查询 SKU 和 Product（消息中的数据不一定是当前最新）
            List<SkuDO> skuList = new ArrayList<>();
            for (OrderCreateMessage.ItemDTO item : items) {
                SkuDO sku = skuMapper.selectById(item.getSkuId());
                if (sku == null) {
                    throw new BusinessException("商品不存在，skuId: " + item.getSkuId());
                }
                ProductDO product = productMapper.selectById(sku.getProductId());
                if (product == null || !Integer.valueOf(1).equals(product.getStatus())) {
                    throw new BusinessException("商品已下架，" + sku.getName());
                }
                skuList.add(sku);
            }

            // ③ 确定 shop_id
            Long shopId = null;
            for (SkuDO sku : skuList) {
                ProductDO p = productMapper.selectById(sku.getProductId());
                if (p != null && p.getShopId() != null) {
                    if (shopId == null) {
                        shopId = p.getShopId();
                    } else if (!shopId.equals(p.getShopId())) {
                        throw new BusinessException("订单中包含多个商家的商品，请分开下单");
                    }
                }
            }
            if (shopId == null) {
                throw new BusinessException("商品所属商家信息缺失");
            }

            // ④ MySQL 行锁扣库存（最终凭证；Redis 预扣已在接口层完成，此处仅做 DB 真实扣减）
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (int i = 0; i < items.size(); i++) {
                OrderCreateMessage.ItemDTO item = items.get(i);
                SkuDO sku = skuList.get(i);
                int affected = skuMapper.deductStock(item.getSkuId(), item.getQuantity());
                if (affected == 0) {
                    throw new BusinessException("库存不足，" + sku.getName());
                }
                totalAmount = totalAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }

            // ⑤ 创建订单
            String orderNo = String.valueOf(snowflake.nextId());
            OrderDO order = new OrderDO();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setShopId(shopId);
            order.setTotalAmount(totalAmount);
            order.setActualAmount(totalAmount);
            order.setDiscountAmount(BigDecimal.ZERO);
            order.setFreight(BigDecimal.ZERO);
            order.setStatus(OrderConstants.STATUS_PENDING_PAY);
            order.setReceiverName(msg.getReceiverName());
            order.setReceiverPhone(msg.getReceiverPhone());
            order.setReceiverAddress(msg.getReceiverAddress());
            order.setRemark(msg.getRemark());
            orderMapper.insert(order);

            // ⑥ 订单商品快照
            for (int i = 0; i < items.size(); i++) {
                OrderCreateMessage.ItemDTO item = items.get(i);
                SkuDO sku = skuList.get(i);

                OrderItemDO orderItem = new OrderItemDO();
                orderItem.setOrderId(order.getId());
                orderItem.setOrderNo(orderNo);
                orderItem.setProductId(sku.getProductId());
                orderItem.setSkuId(sku.getId());
                orderItem.setSkuName(sku.getName());
                orderItem.setPrice(sku.getPrice());
                orderItem.setQuantity(item.getQuantity());
                orderItem.setSubtotal(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));

                ProductDO product = productMapper.selectById(sku.getProductId());
                if (product != null) {
                    orderItem.setProductName(product.getName());
                    orderItem.setProductMainImage(product.getMainImage());
                }
                orderItemMapper.insert(orderItem);
            }

            // ⑦ 购物车标记已下单
            for (OrderCreateMessage.ItemDTO item : items) {
                CartDO cartItem = cartMapper.selectOne(
                        new LambdaQueryWrapper<CartDO>()
                                .eq(CartDO::getUserId, userId)
                                .eq(CartDO::getSkuId, item.getSkuId())
                                .eq(CartDO::getIsOrdered, 0));
                if (cartItem != null) {
                    cartItem.setIsOrdered(1);
                    cartMapper.updateById(cartItem);
                }
            }

            // ⑧ 订单缓存
            redisTemplate.opsForValue().set(
                    "order:detail:" + order.getId(), orderNo,
                    OrderConstants.PAY_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            // ⑨ 淘汰商品缓存
            for (SkuDO sku : skuList) {
                redisTemplate.delete(ProductConstants.CACHE_PRODUCT + sku.getProductId());
            }

            log.info("异步落单成功, orderNo: {}, userId: {}, shopId: {}, amount: {}", orderNo, userId, shopId, totalAmount);
            return order.getId();

        } catch (BusinessException e) {
            // 业务异常（不重试）：标记业务失败，事务回滚后由事务同步恢复接口预扣的库存
            businessFailed[0] = true;
            throw e;
        }
    }

    /**
     * 查询异步落单状态（供前端轮询）：从 Redis 读取 PROCESSING / 订单ID / FAILED 等状态
     *
     * @param processingId 下单时返回的 orderToken，必填
     * @return 状态 Map；Redis 中已无该 key 时返回 status=EXPIRED
     */
    @Override
    public Map<String, Object> getProcessingStatus(String processingId) {
        String status = redisTemplate.opsForValue().get("order:process:" + processingId);
        if (status == null) {
            return Map.of("status", "EXPIRED");
        }
        return Map.of("status", status);
    }

    /**
     * 支付回调处理：条件更新仅「待支付」订单可置为已支付并写入支付时间/方式（WHERE 待支付限定），
     * 幂等防重复回调——受影响行数为 0 时视为重复回调直接返回
     *
     * @param orderNo 订单号，必填
     * @param payType 支付方式（对应支付类型编码）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePaymentCallback(String orderNo, Integer payType) {
        // 条件更新：只有待支付的订单才能变更为已支付（防重复回调/并发覆盖）
        int affected = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderNo, orderNo)
                .eq(OrderDO::getStatus, OrderConstants.STATUS_PENDING_PAY)
                .set(OrderDO::getStatus, OrderConstants.STATUS_PAID)
                .set(OrderDO::getPayTime, LocalDateTime.now())
                .set(OrderDO::getPayType, payType));
        if (affected == 0) {
            log.info("支付回调重复处理, orderNo: {}", orderNo);
            return;
        }
        log.info("支付回调处理成功, orderNo: {}, payType: {}", orderNo, payType);
    }

    /**
     * 取消订单：校验订单归属与待支付状态后，逐项回滚 MySQL 库存与 Redis 库存、淘汰商品缓存，再将订单置为已取消
     *
     * @param orderId 订单ID，必填
     * @throws BusinessException 订单不存在、无权操作该订单（非本人订单）、当前订单状态不允许取消（仅待支付可取消）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该订单");
        }
        if (!Objects.equals(order.getStatus(), OrderConstants.STATUS_PENDING_PAY)) {
            throw new BusinessException("当前订单状态不允许取消");
        }
        // 回滚库存
        List<OrderItemDO> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getOrderId, orderId));
        for (OrderItemDO item : items) {
            skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
            stockCacheService.restoreStock(item.getSkuId(), item.getQuantity());
            redisTemplate.delete(ProductConstants.CACHE_PRODUCT + item.getProductId());
        }
        order.setStatus(OrderConstants.STATUS_CANCELLED);
        orderMapper.updateById(order);
        log.info("订单已取消, orderNo: {}", order.getOrderNo());
    }

    /**
     * 按指定 userId 取消订单（AI 客服工具调用用）
     * 与 cancelOrder 逻辑一致：归属校验 + 状态机（仅待支付可取消）+ 逐项回滚 MySQL/Redis 库存 + 淘汰商品缓存；
     * 唯一区别是 userId 由入参显式传入（不依赖 StpUtil ThreadLocal，agent loop 异步线程可用）
     *
     * @param orderId 订单ID
     * @param userId  用户ID
     * @throws BusinessException 订单不存在、无权操作该订单、当前订单状态不允许取消
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrderByUserId(Long orderId, Long userId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该订单");
        }
        // 回滚库存依赖订单项，先查出来（真正是否回滚由下面的条件更新结果决定）
        List<OrderItemDO> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getOrderId, orderId));

        // 条件更新保证幂等：并发取消同一订单时，只有一次能把「待支付」改成「已取消」。
        // 若先回滚库存再无条件 updateById，两个请求都能通过上面的状态检查，库存会被回滚两次。
        int updated = orderMapper.update(null,
                new LambdaUpdateWrapper<OrderDO>()
                        .eq(OrderDO::getId, orderId)
                        .eq(OrderDO::getUserId, userId)
                        .eq(OrderDO::getStatus, OrderConstants.STATUS_PENDING_PAY)
                        .set(OrderDO::getStatus, OrderConstants.STATUS_CANCELLED));
        if (updated == 0) {
            throw new BusinessException("当前订单状态不允许取消");
        }

        // 只有抢到状态流转的这一次才回滚库存
        for (OrderItemDO item : items) {
            skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
            stockCacheService.restoreStock(item.getSkuId(), item.getQuantity());
            redisTemplate.delete(ProductConstants.CACHE_PRODUCT + item.getProductId());
        }
        log.info("订单已取消, orderNo: {}", order.getOrderNo());
    }

    /**
     * 确认收货：校验订单归属与已发货状态后置为已收货并记录收货时间
     *
     * @param orderId 订单ID，必填
     * @throws BusinessException 订单不存在、无权操作该订单、当前订单状态不允许确认收货（仅已发货可确认）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceive(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该订单");
        }
        if (!Objects.equals(order.getStatus(), OrderConstants.STATUS_DELIVERED)) {
            throw new BusinessException("当前订单状态不允许确认收货");
        }
        order.setStatus(OrderConstants.STATUS_RECEIVED);
        order.setReceiveTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单确认收货, orderNo: {}", order.getOrderNo());
    }

    /**
     * 查询当前用户的订单详情（含订单项快照与状态文案），校验订单归属
     *
     * @param orderId 订单ID，必填
     * @return 订单详情 VO（含 items 订单项列表）
     * @throws BusinessException 订单不存在、无权查看该订单（非本人订单）
     */
    @Override
    public OrderVO getDetail(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权查看该订单");
        }
        return convertToVO(order);
    }

    /**
     * 查询当前用户订单分页列表（按创建时间倒序），可按订单状态筛选
     *
     * @param page     页码，为空或非法时由 PaginationHelper 归一化
     * @param pageSize 每页条数，为空或非法时归一化
     * @param status   订单状态筛选，可为空表示全部
     * @return 当前用户订单分页结果 {@code PageResult<OrderVO>}
     */
    @Override
    public PageResult<OrderVO> myOrders(Integer page, Integer pageSize, Integer status) {
        int[] pn = PaginationHelper.normalize(page, pageSize);
        page = pn[0];
        pageSize = pn[1];

        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        Page<OrderDO> mpPage = new Page<>(page, pageSize);
        LambdaQueryWrapper<OrderDO> wrapper = new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, userId)
                .orderByDesc(OrderDO::getCreateTime);
        if (status != null) {
            wrapper.eq(OrderDO::getStatus, status);
        }
        IPage<OrderDO> iPage = orderMapper.selectPage(mpPage, wrapper);
        List<OrderVO> voList = iPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());
        return PageResult.of(iPage, voList);
    }

    /**
     * 按指定 userId 查询订单列表（AI 客服工具调用用）
     * 与 myOrders 的区别：userId 显式传入，不依赖 StpUtil 的 ThreadLocal 登录态
     *
     * @param userId 用户ID
     * @param status 订单状态（null=全部）
     * @param limit  返回条数上限
     * @return 订单列表 VO（含商品快照与状态文案）
     */
    @Override
    public List<OrderVO> myOrdersByUserId(Long userId, Integer status, int limit) {
        LambdaQueryWrapper<OrderDO> wrapper = new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, userId)
                .orderByDesc(OrderDO::getCreateTime);
        if (status != null) {
            wrapper.eq(OrderDO::getStatus, status);
        }
        wrapper.last("LIMIT " + limit);
        return orderMapper.selectList(wrapper).stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderVO> myOrdersByUserIdAndTime(Long userId, Integer status,
                                                 LocalDateTime start, LocalDateTime end, int limit) {
        LambdaQueryWrapper<OrderDO> wrapper = new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, userId)
                // 时间条件下推到 SQL：先拉列表再内存过滤会漏单（limit 截断发生在过滤之前）
                .ge(start != null, OrderDO::getCreateTime, start)
                .le(end != null, OrderDO::getCreateTime, end)
                .orderByDesc(OrderDO::getCreateTime);
        if (status != null) {
            wrapper.eq(OrderDO::getStatus, status);
        }
        wrapper.last("LIMIT " + limit);
        return orderMapper.selectList(wrapper).stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    /**
     * 按指定 userId 查询订单详情（AI 客服工具调用用，内置归属校验）
     *
     * @param orderId 订单ID
     * @param userId  用户ID
     * @return 订单详情 VO
     * @throws BusinessException 订单不存在或非本人订单时抛出
     */
    @Override
    public OrderVO getOrderDetailByUserId(Long orderId, Long userId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权查看该订单");
        }
        return convertToVO(order);
    }

    // ==================== 管理员端 ====================

    /**
     * 管理员端订单分页查询：按创建时间倒序，支持状态、商家、时间范围、关键字（订单号/收件人姓名/电话）多条件筛选
     *
     * @param page      页码，从 1 开始
     * @param pageSize  每页条数
     * @param status    订单状态筛选，可为空
     * @param shopId    商家ID筛选，可为空
     * @param startTime 开始日期（yyyy-MM-dd，含当天 00:00:00），可为空
     * @param endTime   结束日期（yyyy-MM-dd，含当天 23:59:59），可为空
     * @param keyword   关键字（订单号/收件人姓名/电话模糊匹配），可为空
     * @return 订单分页结果 {@code PageResult<OrderVO>}
     * @throws BusinessException 开始/结束时间格式错误，需为 yyyy-MM-dd
     */
    @Override
    public PageResult<OrderVO> adminPageOrders(int page, int pageSize, Integer status,
                                               Long shopId, String startTime, String endTime, String keyword) {
        Page<OrderDO> mpPage = new Page<>(page, pageSize);
        LambdaQueryWrapper<OrderDO> wrapper = new LambdaQueryWrapper<OrderDO>()
                .orderByDesc(OrderDO::getCreateTime);

        if (status != null) {
            wrapper.eq(OrderDO::getStatus, status);
        }
        if (shopId != null) {
            wrapper.eq(OrderDO::getShopId, shopId);
        }
        if (startTime != null && !startTime.isBlank()) {
            try {
                wrapper.ge(OrderDO::getCreateTime, LocalDateTime.parse(startTime + "T00:00:00"));
            } catch (DateTimeParseException e) {
                throw new BusinessException("开始时间格式错误，请使用 yyyy-MM-dd 格式");
            }
        }
        if (endTime != null && !endTime.isBlank()) {
            try {
                wrapper.le(OrderDO::getCreateTime, LocalDateTime.parse(endTime + "T23:59:59"));
            } catch (DateTimeParseException e) {
                throw new BusinessException("结束时间格式错误，请使用 yyyy-MM-dd 格式");
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(OrderDO::getOrderNo, keyword)
                    .or().like(OrderDO::getReceiverName, keyword)
                    .or().like(OrderDO::getReceiverPhone, keyword));
        }

        IPage<OrderDO> iPage = orderMapper.selectPage(mpPage, wrapper);
        List<OrderVO> voList = iPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());
        return PageResult.of(iPage, voList);
    }

    /**
     * 管理员端查看任意订单详情（全平台，不做归属校验）
     *
     * @param orderId 订单ID，必填
     * @return 订单详情 VO
     * @throws BusinessException 订单不存在
     */
    @Override
    public OrderVO adminGetDetail(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return convertToVO(order);
    }

    // ==================== 商家端 ====================

    /**
     * 商家端订单分页查询：shop_id 取自当前登录商家并强制作为查询条件，数据隔离，仅返回本店订单
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数
     * @param status   订单状态筛选，可为空
     * @return 本店订单分页结果 {@code PageResult<OrderVO>}
     */
    @Override
    public PageResult<OrderVO> merchantPageOrders(int page, int pageSize, Integer status) {
        Long shopId = StpShopUtil.INSTANCE.getLoginIdAsLong();
        Page<OrderDO> mpPage = new Page<>(page, pageSize);
        LambdaQueryWrapper<OrderDO> wrapper = new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getShopId, shopId)
                .orderByDesc(OrderDO::getCreateTime);
        if (status != null) {
            wrapper.eq(OrderDO::getStatus, status);
        }
        IPage<OrderDO> iPage = orderMapper.selectPage(mpPage, wrapper);
        List<OrderVO> voList = iPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());
        return PageResult.of(iPage, voList);
    }

    /**
     * 商家端查看本店订单详情，校验订单归属（shop_id 匹配，非本店订单禁止查看）
     *
     * @param orderId 订单ID，必填
     * @return 订单详情 VO
     * @throws BusinessException 订单不存在、无权查看该订单（非本店订单）
     */
    @Override
    public OrderVO merchantGetDetail(Long orderId) {
        Long shopId = StpShopUtil.INSTANCE.getLoginIdAsLong();
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!shopId.equals(order.getShopId())) {
            throw new BusinessException("无权查看该订单（非本店订单）");
        }
        return convertToVO(order);
    }

    /**
     * 商家发货：校验订单归属与已支付状态后置为已发货，记录发货时间与物流单号
     *
     * @param orderId     订单ID，必填
     * @param logisticsNo 物流单号，必填
     * @throws BusinessException 订单不存在、无权操作该订单（非本店订单）、当前订单状态不允许发货（仅已支付可发货）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void merchantDeliver(Long orderId, String logisticsNo) {
        Long shopId = StpShopUtil.INSTANCE.getLoginIdAsLong();
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!shopId.equals(order.getShopId())) {
            throw new BusinessException("无权操作该订单（非本店订单）");
        }
        if (!Objects.equals(order.getStatus(), OrderConstants.STATUS_PAID)) {
            throw new BusinessException("当前订单状态不允许发货，仅已支付订单可发货");
        }
        order.setStatus(OrderConstants.STATUS_DELIVERED);
        order.setDeliverTime(LocalDateTime.now());
        order.setLogisticsNo(logisticsNo);
        orderMapper.updateById(order);
        log.info("商家发货成功, orderNo: {}, logisticsNo: {}", order.getOrderNo(), logisticsNo);
    }

    /** 订单DO转VO */
    private OrderVO convertToVO(OrderDO order) {
        OrderVO vo = new OrderVO();
        BeanUtil.copyProperties(order, vo);
        vo.setStatusText(OrderConstants.getStatusText(order.getStatus()));

        List<OrderItemDO> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getOrderId, order.getId()));

        List<OrderVO.OrderItemVO> itemVOList = items.stream().map(item -> {
            OrderVO.OrderItemVO itemVO = new OrderVO.OrderItemVO();
            BeanUtil.copyProperties(item, itemVO);
            // productMainImage → productImage（字段名不同，手动拷贝）
            itemVO.setProductImage(item.getProductMainImage());
            return itemVO;
        }).collect(Collectors.toList());
        vo.setItems(itemVOList);

        return vo;
    }
}
