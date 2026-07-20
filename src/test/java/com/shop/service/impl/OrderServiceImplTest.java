package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.constant.OrderConstants;
import com.shop.mapper.CartMapper;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.entity.*;
import com.shop.model.vo.OrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderService 单元测试
 * 覆盖订单完整生命周期：防重令牌 → 创建 → 支付回调 → 取消 → 确认收货 → 各种查询/商家操作
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService - 订单服务")
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private SkuMapper skuMapper;

    @Mock
    private CartMapper cartMapper;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OrderServiceImpl orderService;

    private OrderDO pendingOrder;
    private OrderDO paidOrder;
    private OrderDO deliveredOrder;
    private OrderCreateDTO createDTO;
    private OrderCreateDTO.OrderItemDTO itemDTO;
    private SkuDO validSku;
    private ProductDO validProduct;
    private CartDO cartItem;
    private OrderItemDO orderItem;

    @BeforeEach
    void setUp() {
        // ========== 订单实体 ==========
        pendingOrder = new OrderDO();
        pendingOrder.setId(1L);
        pendingOrder.setOrderNo("20240101001");
        pendingOrder.setUserId(1L);
        pendingOrder.setShopId(10L);
        pendingOrder.setTotalAmount(new BigDecimal("198.00"));
        pendingOrder.setActualAmount(new BigDecimal("198.00"));
        pendingOrder.setStatus(OrderConstants.STATUS_PENDING_PAY);

        paidOrder = new OrderDO();
        paidOrder.setId(2L);
        paidOrder.setOrderNo("20240101002");
        paidOrder.setUserId(1L);
        paidOrder.setShopId(10L);
        paidOrder.setStatus(OrderConstants.STATUS_PAID);

        deliveredOrder = new OrderDO();
        deliveredOrder.setId(3L);
        deliveredOrder.setOrderNo("20240101003");
        deliveredOrder.setUserId(1L);
        deliveredOrder.setShopId(10L);
        deliveredOrder.setStatus(OrderConstants.STATUS_DELIVERED);

        // ========== 下单参数 ==========
        itemDTO = new OrderCreateDTO.OrderItemDTO();
        itemDTO.setSkuId(100L);
        itemDTO.setQuantity(2);

        createDTO = new OrderCreateDTO();
        createDTO.setToken("test-token-123");
        createDTO.setReceiverName("张三");
        createDTO.setReceiverPhone("13800138000");
        createDTO.setReceiverAddress("北京市朝阳区");
        createDTO.setRemark("请尽快发货");
        createDTO.setItems(List.of(itemDTO));

        // ========== SKU & Product ==========
        validSku = new SkuDO();
        validSku.setId(100L);
        validSku.setProductId(10L);
        validSku.setName("测试SKU");
        validSku.setPrice(new BigDecimal("99.00"));
        validSku.setStock(50);

        validProduct = new ProductDO();
        validProduct.setId(10L);
        validProduct.setName("测试商品");
        validProduct.setMainImage("http://example.com/img.jpg");
        validProduct.setShopId(10L);
        validProduct.setStatus(1);

        // ========== 购物车 ==========
        cartItem = new CartDO();
        cartItem.setId(1L);
        cartItem.setUserId(1L);
        cartItem.setSkuId(100L);
        cartItem.setQuantity(2);
        cartItem.setIsOrdered(0);

        // ========== 订单项 ==========
        orderItem = new OrderItemDO();
        orderItem.setId(1L);
        orderItem.setOrderId(1L);
        orderItem.setOrderNo("20240101001");
        orderItem.setSkuId(100L);
        orderItem.setSkuName("测试SKU");
        orderItem.setPrice(new BigDecimal("99.00"));
        orderItem.setQuantity(2);
        orderItem.setSubtotal(new BigDecimal("198.00"));
    }

    // ==================== 防重令牌 ====================

    @Nested
    @DisplayName("获取防重令牌 - getOrderToken()")
    class GetOrderTokenTest {

        @Test
        @DisplayName("成功生成并缓存令牌")
        void getOrderToken_ShouldGenerateAndCache() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.opsForValue()).thenReturn(valueOperations);

                String token = orderService.getOrderToken();

                assertNotNull(token);
                assertFalse(token.isEmpty());
                verify(valueOperations).set(
                    eq("order:token:1"),
                    anyString(),
                    eq(5L),
                    eq(TimeUnit.MINUTES));
            }
        }
    }

    // ==================== 创建订单 ====================

    @Nested
    @DisplayName("创建订单 - createOrder()")
    class CreateOrderTest {

        @Test
        @DisplayName("成功创建订单：完整流程")
        void createOrder_ShouldSucceed() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                // Token 校验
                when(redisTemplate.delete("order:token:1")).thenReturn(true);

                // SKU 校验
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);

                // 插入订单并设置 ID
                doAnswer(inv -> { inv.<OrderDO>getArgument(0).setId(1L); return 0; })
                        .when(orderMapper).insert(any(OrderDO.class));

                // 扣库存
                when(skuMapper.deductStock(100L, 2)).thenReturn(1);

                // Redis 缓存 & 购物车标记
                when(redisTemplate.opsForValue()).thenReturn(valueOperations);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cartItem);

                Long orderId = orderService.createOrder(createDTO);

                assertNotNull(orderId);
                verify(orderMapper).insert(any(OrderDO.class));
                verify(orderItemMapper).insert(any(OrderItemDO.class));
                verify(skuMapper).deductStock(100L, 2);
                verify(cartMapper).updateById(argThat(c -> c.getIsOrdered() == 1));
                verify(valueOperations).set(
                    startsWith("order:detail:"),
                    anyString(),
                    eq(30L),
                    eq(TimeUnit.MINUTES));
            }
        }

        @Test
        @DisplayName("创建失败：Token 校验失败（重复提交）")
        void createOrder_ShouldThrow_WhenTokenInvalid() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(false);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertEquals("请勿重复提交订单", ex.getMessage());
                verify(orderMapper, never()).insert(any());
            }
        }

        @Test
        @DisplayName("创建失败：Token 删除返回 null")
        void createOrder_ShouldThrow_WhenTokenDeleteNull() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(null);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertEquals("请勿重复提交订单", ex.getMessage());
            }
        }

        @Test
        @DisplayName("创建失败：商品列表为空")
        void createOrder_ShouldThrow_WhenItemsEmpty() {
            createDTO.setItems(List.of());
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertEquals("请选择商品", ex.getMessage());
            }
        }

        @Test
        @DisplayName("创建失败：SKU 不存在")
        void createOrder_ShouldThrow_WhenSkuNotFound() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(null);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertTrue(ex.getMessage().contains("商品不存在"));
            }
        }

        @Test
        @DisplayName("创建失败：商品已下架")
        void createOrder_ShouldThrow_WhenProductOffShelf() {
            validProduct.setStatus(0); // 下架
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertTrue(ex.getMessage().contains("已下架"));
            }
        }

        @Test
        @DisplayName("创建失败：库存不足（校验阶段）")
        void createOrder_ShouldThrow_WhenStockInsufficient() {
            itemDTO.setQuantity(100); // 需求100 > 库存50
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertTrue(ex.getMessage().contains("库存不足"));
            }
        }

        @Test
        @DisplayName("创建失败：扣库存行锁返回 0（并发超卖）")
        void createOrder_ShouldThrow_WhenDeductStockFails() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);
                when(skuMapper.deductStock(100L, 2)).thenReturn(0); // 扣库存失败

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertTrue(ex.getMessage().contains("库存不足"));
            }
        }

        @Test
        @DisplayName("创建失败：多个商家的商品")
        void createOrder_ShouldThrow_WhenMultiShop() {
            // 两个不同商家的 SKU
            OrderCreateDTO.OrderItemDTO itemDTO2 = new OrderCreateDTO.OrderItemDTO();
            itemDTO2.setSkuId(200L);
            itemDTO2.setQuantity(1);
            createDTO.setItems(List.of(itemDTO, itemDTO2));

            SkuDO sku2 = new SkuDO();
            sku2.setId(200L);
            sku2.setProductId(20L);
            sku2.setName("另一家店的商品");
            sku2.setStock(10);

            ProductDO product2 = new ProductDO();
            product2.setId(20L);
            product2.setShopId(20L); // 不同商家
            product2.setStatus(1);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(skuMapper.selectById(200L)).thenReturn(sku2);
                when(productMapper.selectById(10L)).thenReturn(validProduct);
                when(productMapper.selectById(20L)).thenReturn(product2);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertEquals("订单中包含多个商家的商品，请分开下单", ex.getMessage());
            }
        }

        @Test
        @DisplayName("创建失败：商品商家信息缺失")
        void createOrder_ShouldThrow_WhenShopIdMissing() {
            validProduct.setShopId(null); // 无商家信息
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertEquals("商品所属商家信息缺失", ex.getMessage());
            }
        }
    }

    // ==================== 支付回调 ====================

    @Nested
    @DisplayName("支付回调 - handlePaymentCallback()")
    class HandlePaymentCallbackTest {

        @Test
        @DisplayName("支付成功：更新订单状态为已支付")
        void handlePaymentCallback_ShouldSucceed() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingOrder);

            orderService.handlePaymentCallback("20240101001", 1);

            verify(orderMapper).updateById(argThat(order ->
                    order.getStatus() == OrderConstants.STATUS_PAID
                            && order.getPayType() == 1
                            && order.getPayTime() != null));
        }

        @Test
        @DisplayName("支付回调：订单不存在时静默忽略")
        void handlePaymentCallback_ShouldSilentlyIgnore_WhenOrderNotFound() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // 不应抛异常
            orderService.handlePaymentCallback("non-existent", 1);

            verify(orderMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("支付回调：已处理过的订单幂等忽略")
        void handlePaymentCallback_ShouldIdempotent_WhenAlreadyPaid() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(paidOrder);

            orderService.handlePaymentCallback("20240101002", 1);

            verify(orderMapper, never()).updateById(any());
        }
    }

    // ==================== 取消订单 ====================

    @Nested
    @DisplayName("取消订单 - cancelOrder()")
    class CancelOrderTest {

        @Test
        @DisplayName("取消成功：恢复库存并更新状态")
        void cancelOrder_ShouldSucceed() {
            // 需要 mock orderItemMapper 查询订单项
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                orderService.cancelOrder(1L);

                verify(skuMapper).restoreStock(100L, 2); // 回滚库存
                verify(orderMapper).updateById(argThat(order ->
                        order.getStatus() == OrderConstants.STATUS_CANCELLED));
            }
        }

        @Test
        @DisplayName("取消失败：订单不存在")
        void cancelOrder_ShouldThrow_WhenOrderNotFound() {
            when(orderMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.cancelOrder(99L));
            assertEquals("订单不存在", ex.getMessage());
        }

        @Test
        @DisplayName("取消失败：非本用户订单")
        void cancelOrder_ShouldThrow_WhenNotOwner() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(2L); // 不同用户

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.cancelOrder(1L));
                assertEquals("无权操作该订单", ex.getMessage());
            }
        }

        @Test
        @DisplayName("取消失败：订单状态不允许取消（已支付）")
        void cancelOrder_ShouldThrow_WhenStatusInvalid() {
            when(orderMapper.selectById(2L)).thenReturn(paidOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.cancelOrder(2L));
                assertEquals("当前订单状态不允许取消", ex.getMessage());
            }
        }
    }

    // ==================== 确认收货 ====================

    @Nested
    @DisplayName("确认收货 - confirmReceive()")
    class ConfirmReceiveTest {

        @Test
        @DisplayName("确认收货成功")
        void confirmReceive_ShouldSucceed() {
            when(orderMapper.selectById(3L)).thenReturn(deliveredOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                orderService.confirmReceive(3L);

                verify(orderMapper).updateById(argThat(order ->
                        order.getStatus() == OrderConstants.STATUS_RECEIVED
                                && order.getReceiveTime() != null));
            }
        }

        @Test
        @DisplayName("确认收货失败：订单不存在")
        void confirmReceive_ShouldThrow_WhenOrderNotFound() {
            when(orderMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.confirmReceive(99L));
            assertEquals("订单不存在", ex.getMessage());
        }

        @Test
        @DisplayName("确认收货失败：非本用户订单")
        void confirmReceive_ShouldThrow_WhenNotOwner() {
            when(orderMapper.selectById(3L)).thenReturn(deliveredOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(2L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.confirmReceive(3L));
                assertEquals("无权操作该订单", ex.getMessage());
            }
        }

        @Test
        @DisplayName("确认收货失败：订单未发货")
        void confirmReceive_ShouldThrow_WhenNotDelivered() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.confirmReceive(1L));
                assertEquals("当前订单状态不允许确认收货", ex.getMessage());
            }
        }
    }

    // ==================== 订单查询 ====================

    @Nested
    @DisplayName("订单查询")
    class OrderQueryTest {

        @Test
        @DisplayName("查询订单详情：成功返回")
        void getDetail_ShouldReturnOrderVO() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                OrderVO vo = orderService.getDetail(1L);

                assertNotNull(vo);
                assertEquals("20240101001", vo.getOrderNo());
                assertEquals("待支付", vo.getStatusText());
                assertEquals(1, vo.getItems().size());
            }
        }

        @Test
        @DisplayName("查询订单详情：订单不存在")
        void getDetail_ShouldThrow_WhenNotFound() {
            when(orderMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.getDetail(99L));
            assertEquals("订单不存在", ex.getMessage());
        }

        @Test
        @DisplayName("查询订单详情：非本人订单")
        void getDetail_ShouldThrow_WhenNotOwner() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(2L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.getDetail(1L));
                assertEquals("无权查看该订单", ex.getMessage());
            }
        }

        @Test
        @DisplayName("我的订单列表：分页查询")
        void myOrders_ShouldReturnPagedResult() {
            Page<OrderDO> pageResult = new Page<>(1, 10);
            pageResult.setRecords(List.of(pendingOrder));
            pageResult.setTotal(1);

            when(orderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(pageResult);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                PageResult<OrderVO> result = orderService.myOrders(1, 10, null);

                assertEquals(1, result.getTotal());
                assertEquals(1, result.getList().size());
                assertEquals("待支付", result.getList().get(0).getStatusText());
            }
        }

        @Test
        @DisplayName("我的订单列表：按状态筛选")
        void myOrders_ShouldFilterByStatus() {
            Page<OrderDO> pageResult = new Page<>(1, 10);
            pageResult.setTotal(0);
            pageResult.setRecords(List.of());

            when(orderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(pageResult);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                PageResult<OrderVO> result = orderService.myOrders(1, 10, OrderConstants.STATUS_PAID);

                assertEquals(0, result.getTotal());
            }
        }

        @Test
        @DisplayName("管理员查询订单列表：所有参数筛选")
        void adminPageOrders_ShouldSupportAllFilters() {
            Page<OrderDO> pageResult = new Page<>(1, 10);
            pageResult.setRecords(List.of(pendingOrder));

            when(orderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(pageResult);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            PageResult<OrderVO> result = orderService.adminPageOrders(
                    1, 10, OrderConstants.STATUS_PENDING_PAY,
                    10L, "2024-01-01", "2024-01-31", "20240101001");

            assertEquals(1, result.getList().size());
        }

        @Test
        @DisplayName("管理员查看订单详情")
        void adminGetDetail_ShouldReturnOrder() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            OrderVO vo = orderService.adminGetDetail(1L);

            assertNotNull(vo);
            assertEquals("20240101001", vo.getOrderNo());
        }

        @Test
        @DisplayName("管理员查看订单详情：订单不存在")
        void adminGetDetail_ShouldThrow_WhenNotFound() {
            when(orderMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.adminGetDetail(99L));
            assertEquals("订单不存在", ex.getMessage());
        }
    }

    // ==================== 商家操作 ====================

    @Nested
    @DisplayName("商家操作")
    class MerchantOperationsTest {

        @Test
        @DisplayName("商家查询订单列表")
        void merchantPageOrders_ShouldReturnOrders() {
            Page<OrderDO> pageResult = new Page<>(1, 10);
            pageResult.setRecords(List.of(pendingOrder));

            when(orderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(pageResult);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            PageResult<OrderVO> result = orderService.merchantPageOrders(1, 10, null, 10L);

            assertEquals(1, result.getList().size());
        }

        @Test
        @DisplayName("商家查询订单列表：shopId 缺失")
        void merchantPageOrders_ShouldThrow_WhenShopIdMissing() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.merchantPageOrders(1, 10, null, null));
            assertEquals("商家ID缺失", ex.getMessage());
        }

        @Test
        @DisplayName("商家查看订单详情：本店订单")
        void merchantGetDetail_ShouldReturn_WhenOwnShop() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            OrderVO vo = orderService.merchantGetDetail(1L, 10L);

            assertNotNull(vo);
        }

        @Test
        @DisplayName("商家查看订单详情：shopId 缺失")
        void merchantGetDetail_ShouldThrow_WhenShopIdMissing() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.merchantGetDetail(1L, null));
            assertEquals("商家ID缺失", ex.getMessage());
        }

        @Test
        @DisplayName("商家查看订单详情：非本店订单")
        void merchantGetDetail_ShouldThrow_WhenNotOwnShop() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.merchantGetDetail(1L, 99L));
            assertEquals("无权查看该订单（非本店订单）", ex.getMessage());
        }

        @Test
        @DisplayName("商家发货成功")
        void merchantDeliver_ShouldSucceed() {
            when(orderMapper.selectById(2L)).thenReturn(paidOrder);

            orderService.merchantDeliver(2L, 10L, "SF123456789");

            verify(orderMapper).updateById(argThat(order ->
                    order.getStatus() == OrderConstants.STATUS_DELIVERED
                            && "SF123456789".equals(order.getLogisticsNo())
                            && order.getDeliverTime() != null));
        }

        @Test
        @DisplayName("商家发货：shopId 缺失")
        void merchantDeliver_ShouldThrow_WhenShopIdMissing() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.merchantDeliver(2L, null, "SF123"));
            assertEquals("商家ID缺失", ex.getMessage());
        }

        @Test
        @DisplayName("商家发货：非本店订单")
        void merchantDeliver_ShouldThrow_WhenNotOwnShop() {
            when(orderMapper.selectById(2L)).thenReturn(paidOrder);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.merchantDeliver(2L, 99L, "SF123"));
            assertEquals("无权操作该订单（非本店订单）", ex.getMessage());
        }

        @Test
        @DisplayName("商家发货：订单未支付")
        void merchantDeliver_ShouldThrow_WhenNotPaid() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.merchantDeliver(1L, 10L, "SF123"));
            assertEquals("当前订单状态不允许发货，仅已支付订单可发货", ex.getMessage());
        }

        @Test
        @DisplayName("商家发货：订单不存在")
        void merchantDeliver_ShouldThrow_WhenOrderNotFound() {
            when(orderMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.merchantDeliver(99L, 10L, "SF123"));
            assertEquals("订单不存在", ex.getMessage());
        }
    }
}
