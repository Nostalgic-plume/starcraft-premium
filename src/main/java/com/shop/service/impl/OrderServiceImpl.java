package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
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
import com.shop.mapper.CartMapper;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 * 覆盖完整订单生命周期：防重令牌 → 创建（库存行锁+shop_id绑定+购物车标记）→ 支付回调（幂等）→ 取消（库存回滚）→ 确认收货
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
    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    @Override
    public String getOrderToken() {
        long userId = StpUtil.getLoginIdAsLong();
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                OrderConstants.CACHE_ORDER_TOKEN + userId,
                token, 5, TimeUnit.MINUTES);
        return token;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(OrderCreateDTO dto) {
        long userId = StpUtil.getLoginIdAsLong();

        // ① Token 防重校验
        Boolean deleted = redisTemplate.delete(OrderConstants.CACHE_ORDER_TOKEN + userId);
        if (deleted == null || !deleted) {
            throw new BusinessException("请勿重复提交订单");
        }

        // ② 参数校验
        List<OrderCreateDTO.OrderItemDTO> items = dto.getItems();
        if (CollUtil.isEmpty(items)) {
            throw new BusinessException("请选择商品");
        }

        List<SkuDO> skuList = new ArrayList<>();
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
            skuList.add(sku);
        }

        // ③ 确定 shop_id（取第一个商品的商家ID，所有商品应属同一商家）
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

        // ④ 价格汇总 + 行锁扣库存
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            OrderCreateDTO.OrderItemDTO item = items.get(i);
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
        order.setReceiverName(dto.getReceiverName());
        order.setReceiverPhone(dto.getReceiverPhone());
        order.setReceiverAddress(dto.getReceiverAddress());
        order.setRemark(dto.getRemark());
        orderMapper.insert(order);

        // ⑥ 插入订单商品快照
        for (int i = 0; i < items.size(); i++) {
            OrderCreateDTO.OrderItemDTO item = items.get(i);
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

        // ⑦ 购物车标记已下单：批量更新 is_ordered = 1
        for (OrderCreateDTO.OrderItemDTO item : items) {
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

        // ⑧ 订单缓存30分钟（方便定时任务判断未支付超时 + 订单详情缓存）
        redisTemplate.opsForValue().set(
                "order:detail:" + order.getId(), orderNo,
                OrderConstants.PAY_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        log.info("订单创建成功, orderNo: {}, userId: {}, shopId: {}, amount: {}", orderNo, userId, shopId, totalAmount);
        return order.getId();
    }

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        long userId = StpUtil.getLoginIdAsLong();
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
        }
        order.setStatus(OrderConstants.STATUS_CANCELLED);
        orderMapper.updateById(order);
        log.info("订单已取消, orderNo: {}", order.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceive(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        long userId = StpUtil.getLoginIdAsLong();
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

    @Override
    public OrderVO getDetail(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        long userId = StpUtil.getLoginIdAsLong();
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权查看该订单");
        }
        return convertToVO(order);
    }

    @Override
    public PageResult<OrderVO> myOrders(Integer page, Integer pageSize, Integer status) {
        int[] pn = PaginationHelper.normalize(page, pageSize);
        page = pn[0];
        pageSize = pn[1];

        long userId = StpUtil.getLoginIdAsLong();
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

    // ==================== 管理员端 ====================

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

    @Override
    public OrderVO adminGetDetail(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return convertToVO(order);
    }

    // ==================== 商家端 ====================

    @Override
    public PageResult<OrderVO> merchantPageOrders(int page, int pageSize, Integer status, Long shopId) {
        if (shopId == null) {
            throw new BusinessException("商家ID缺失");
        }
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

    @Override
    public OrderVO merchantGetDetail(Long orderId, Long shopId) {
        if (shopId == null) {
            throw new BusinessException("商家ID缺失");
        }
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!shopId.equals(order.getShopId())) {
            throw new BusinessException("无权查看该订单（非本店订单）");
        }
        return convertToVO(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void merchantDeliver(Long orderId, Long shopId, String logisticsNo) {
        if (shopId == null) {
            throw new BusinessException("商家ID缺失");
        }
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
