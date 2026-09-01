package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.common.SaTokenMockUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.constant.OrderConstants;
import com.shop.mapper.CartMapper;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
import com.shop.mq.OrderCreateMessage;
import com.shop.mq.OrderProducer;
import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.entity.*;
import com.shop.model.vo.OrderVO;
import com.shop.service.StockCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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

    @Mock
    private OrderProducer orderProducer;

    @Mock
    private StockCacheService stockCacheService;

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
        // 纯 Mockito 无 Spring 上下文：LambdaUpdateWrapper.set() 构造时立即解析列名，
        // 需手动初始化 MyBatis-Plus TableInfo 缓存（幂等，重复调用直接返回已有缓存）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderDO.class);

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
            try (var login = SaTokenMockUtil.userLogin(1L)) {
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
        @DisplayName("成功提交下单：异步发送消息并返回 processingId")
        void createOrder_ShouldSucceed() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                // Token 校验
                when(redisTemplate.delete("order:token:1")).thenReturn(true);

                // SKU / 商品校验
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);

                // Redis 原子预扣库存成功
                when(stockCacheService.tryDeduct(100L, 2)).thenReturn(true);

                // 缓存 PROCESSING 状态 & 异步发送 MQ
                when(redisTemplate.opsForValue()).thenReturn(valueOperations);
                doNothing().when(orderProducer).sendOrderCreate(any(OrderCreateMessage.class));

                String processingId = orderService.createOrder(createDTO);

                assertNotNull(processingId);
                assertEquals(32, processingId.length());
                // 接口层完成预扣；DB 写入不在 createOrder 内
                verify(stockCacheService).tryDeduct(100L, 2);
                verify(stockCacheService, never()).restoreStock(anyLong(), anyInt());
                verify(orderMapper, never()).insert(any(OrderDO.class));
                verify(orderProducer).sendOrderCreate(any(OrderCreateMessage.class));
                verify(valueOperations).set(
                    startsWith("order:process:"),
                    eq("PROCESSING"),
                    eq(5L),
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
        @DisplayName("创建失败：Redis 原子预扣库存不足（秒杀快速失败）")
        void createOrder_ShouldThrow_WhenRedisDeductFails() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);
                // Redis 原子预扣失败 → 库存不足，快速失败不投消息
                when(stockCacheService.tryDeduct(100L, 2)).thenReturn(false);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertTrue(ex.getMessage().contains("库存不足"));
                verify(orderProducer, never()).sendOrderCreate(any(OrderCreateMessage.class));
                verify(stockCacheService, never()).restoreStock(anyLong(), anyInt());
            }
        }

        @Test
        @DisplayName("创建失败：MQ 发送失败恢复已预扣库存")
        void createOrder_ShouldRestoreDeduct_WhenSendFails() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(redisTemplate.delete("order:token:1")).thenReturn(true);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(validProduct);
                when(stockCacheService.tryDeduct(100L, 2)).thenReturn(true);
                when(redisTemplate.opsForValue()).thenReturn(valueOperations);
                // MQ 发送失败
                doThrow(new RuntimeException("MQ down"))
                        .when(orderProducer).sendOrderCreate(any(OrderCreateMessage.class));

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.createOrder(createDTO));
                assertEquals("系统繁忙，请稍后重试", ex.getMessage());
                verify(stockCacheService).restoreStock(100L, 2); // 已预扣库存被恢复
            }
        }

    }

    // ==================== 支付回调 ====================

    @Nested
    @DisplayName("支付回调 - handlePaymentCallback()")
    class HandlePaymentCallbackTest {

        @Test
        @DisplayName("支付成功：按订单号条件更新状态为已支付")
        void handlePaymentCallback_ShouldSucceed() {
            when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

            orderService.handlePaymentCallback("20240101001", 1);

            verify(orderMapper).update(isNull(), argThat(wrapper -> {
                String where = wrapper.getSqlSegment().toLowerCase();
                String set = wrapper.getSqlSet().toLowerCase();
                return where.contains("order_no") && set.contains("status");
            }));
        }

        @Test
        @DisplayName("支付回调：无待支付订单时静默忽略")
        void handlePaymentCallback_ShouldSilentlyIgnore_WhenOrderNotFound() {
            when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

            // 不应抛异常
            orderService.handlePaymentCallback("non-existent", 1);

            verify(orderMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        }

        @Test
        @DisplayName("支付回调：已处理过的订单幂等忽略")
        void handlePaymentCallback_ShouldIdempotent_WhenAlreadyPaid() {
            when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

            orderService.handlePaymentCallback("20240101002", 1);

            verify(orderMapper).update(isNull(), any(LambdaUpdateWrapper.class));
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

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(10L);

                PageResult<OrderVO> result = orderService.merchantPageOrders(1, 10, null);

                assertEquals(1, result.getList().size());
            }
        }

        @Test
        @DisplayName("商家查看订单详情：本店订单")
        void merchantGetDetail_ShouldReturn_WhenOwnShop() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);
            when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(10L);

                OrderVO vo = orderService.merchantGetDetail(1L);

                assertNotNull(vo);
            }
        }

        @Test
        @DisplayName("商家查看订单详情：非本店订单")
        void merchantGetDetail_ShouldThrow_WhenNotOwnShop() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.merchantGetDetail(1L));
                assertEquals("无权查看该订单（非本店订单）", ex.getMessage());
            }
        }

        @Test
        @DisplayName("商家发货成功")
        void merchantDeliver_ShouldSucceed() {
            when(orderMapper.selectById(2L)).thenReturn(paidOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(10L);

                orderService.merchantDeliver(2L, "SF123456789");

                verify(orderMapper).updateById(argThat(order ->
                        order.getStatus() == OrderConstants.STATUS_DELIVERED
                                && "SF123456789".equals(order.getLogisticsNo())
                                && order.getDeliverTime() != null));
            }
        }

        @Test
        @DisplayName("商家发货：非本店订单")
        void merchantDeliver_ShouldThrow_WhenNotOwnShop() {
            when(orderMapper.selectById(2L)).thenReturn(paidOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.merchantDeliver(2L, "SF123"));
                assertEquals("无权操作该订单（非本店订单）", ex.getMessage());
            }
        }

        @Test
        @DisplayName("商家发货：订单未支付")
        void merchantDeliver_ShouldThrow_WhenNotPaid() {
            when(orderMapper.selectById(1L)).thenReturn(pendingOrder);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(10L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.merchantDeliver(1L, "SF123"));
                assertEquals("当前订单状态不允许发货，仅已支付订单可发货", ex.getMessage());
            }
        }

        @Test
        @DisplayName("商家发货：订单不存在")
        void merchantDeliver_ShouldThrow_WhenOrderNotFound() {
            when(orderMapper.selectById(99L)).thenReturn(null);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(10L);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> orderService.merchantDeliver(99L, "SF123"));
                assertEquals("订单不存在", ex.getMessage());
            }
        }
    }
}
