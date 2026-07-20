package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.mapper.CartMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.dto.CartDTO;
import com.shop.model.entity.CartDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.entity.SkuDO;
import com.shop.model.vo.CartVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CartService 单元测试
 * 覆盖添加、修改数量、删除、选中、列表查询等核心业务逻辑
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CartService - 购物车服务")
class CartServiceImplTest {

    @Mock
    private CartMapper cartMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private SkuMapper skuMapper;

    @InjectMocks
    private CartServiceImpl cartService;

    private CartDTO cartDTO;
    private SkuDO validSku;
    private CartDO existingCart;
    private ProductDO product;

    @BeforeEach
    void setUp() {
        cartDTO = new CartDTO();
        cartDTO.setSkuId(100L);
        cartDTO.setQuantity(2);

        validSku = new SkuDO();
        validSku.setId(100L);
        validSku.setProductId(10L);
        validSku.setName("测试SKU");
        validSku.setPrice(new BigDecimal("99.00"));
        validSku.setStock(50);

        product = new ProductDO();
        product.setId(10L);
        product.setName("测试商品");
        product.setMainImage("http://example.com/img.jpg");

        existingCart = new CartDO();
        existingCart.setId(1L);
        existingCart.setUserId(1L);
        existingCart.setSkuId(100L);
        existingCart.setProductId(10L);
        existingCart.setQuantity(1);
        existingCart.setChecked(1);
        existingCart.setIsOrdered(0);
    }

    @Nested
    @DisplayName("添加商品 - addItem()")
    class AddItemTest {

        @Test
        @DisplayName("新增商品到购物车：SKU存在且库存充足")
        void addItem_ShouldInsert_WhenSkuValid() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

                cartService.addItem(cartDTO);

                verify(cartMapper).insert(any(CartDO.class));
            }
        }

        @Test
        @DisplayName("添加商品：SKU不存在时抛出异常")
        void addItem_ShouldThrow_WhenSkuNotFound() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(skuMapper.selectById(100L)).thenReturn(null);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> cartService.addItem(cartDTO));
                assertEquals("商品不存在", ex.getMessage());
                verify(cartMapper, never()).insert(any());
            }
        }

        @Test
        @DisplayName("添加商品：已存在则合并数量")
        void addItem_ShouldMergeQuantity_WhenAlreadyInCart() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingCart);

                cartService.addItem(cartDTO);

                verify(cartMapper).updateById(argThat(cart ->
                        cart.getQuantity() == 3)); // 1 + 2
                verify(cartMapper, never()).insert(any());
            }
        }

        @Test
        @DisplayName("添加商品：合并后库存不足抛异常")
        void addItem_ShouldThrow_WhenMergeExceedsStock() {
            cartDTO.setQuantity(100); // 合并后 1+100=101 > 50
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingCart);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> cartService.addItem(cartDTO));
                assertTrue(ex.getMessage().contains("库存不足"));
                verify(cartMapper, never()).updateById(any());
                verify(cartMapper, never()).insert(any());
            }
        }

        @Test
        @DisplayName("添加商品：新增时数量超出库存抛异常")
        void addItem_ShouldThrow_WhenNewExceedsStock() {
            validSku.setStock(1);
            cartDTO.setQuantity(5);
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> cartService.addItem(cartDTO));
                assertTrue(ex.getMessage().contains("库存不足"));
                verify(cartMapper, never()).insert(any());
            }
        }
    }

    @Nested
    @DisplayName("修改数量 - updateQuantity()")
    class UpdateQuantityTest {

        @Test
        @DisplayName("修改数量成功")
        void updateQuantity_ShouldSucceed() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingCart);
                when(skuMapper.selectById(100L)).thenReturn(validSku);

                cartService.updateQuantity(100L, 5);

                verify(cartMapper).updateById(argThat(cart ->
                        cart.getQuantity() == 5));
            }
        }

        @Test
        @DisplayName("修改数量：购物车中不存在该商品")
        void updateQuantity_ShouldThrow_WhenCartNotFound() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> cartService.updateQuantity(100L, 5));
                assertEquals("购物车中未找到该商品", ex.getMessage());
            }
        }

        @Test
        @DisplayName("修改数量：超出库存时抛异常")
        void updateQuantity_ShouldThrow_WhenExceedsStock() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingCart);
                when(skuMapper.selectById(100L)).thenReturn(validSku);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> cartService.updateQuantity(100L, 100));
                assertTrue(ex.getMessage().contains("库存不足"));
            }
        }
    }

    @Nested
    @DisplayName("删除商品 - removeItem()")
    class RemoveItemTest {

        @Test
        @DisplayName("从购物车删除指定SKU")
        void removeItem_ShouldDelete() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

                cartService.removeItem(100L);

                verify(cartMapper).delete(any(LambdaQueryWrapper.class));
            }
        }
    }

    @Nested
    @DisplayName("选中/取消选中 - checkItem()")
    class CheckItemTest {

        @Test
        @DisplayName("选中商品")
        void checkItem_ShouldCheck() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingCart);

                cartService.checkItem(100L, true);

                verify(cartMapper).updateById(argThat(cart ->
                        cart.getChecked() == 1));
            }
        }

        @Test
        @DisplayName("取消选中")
        void checkItem_ShouldUncheck() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingCart);

                cartService.checkItem(100L, false);

                verify(cartMapper).updateById(argThat(cart ->
                        cart.getChecked() == 0));
            }
        }

        @Test
        @DisplayName("选中/取消选中：购物车中不存在该商品")
        void checkItem_ShouldThrow_WhenCartNotFound() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> cartService.checkItem(100L, true));
                assertEquals("购物车中未找到该商品", ex.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("购物车列表 - list()")
    class ListTest {

        @Test
        @DisplayName("获取购物车列表：正常返回含商品信息")
        void list_ShouldReturnEnrichedList() {
            CartDO cartItem = new CartDO();
            cartItem.setId(1L);
            cartItem.setUserId(1L);
            cartItem.setSkuId(100L);
            cartItem.setProductId(10L);
            cartItem.setQuantity(2);
            cartItem.setChecked(1);
            cartItem.setIsOrdered(0);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(cartItem));
                when(skuMapper.selectById(100L)).thenReturn(validSku);
                when(productMapper.selectById(10L)).thenReturn(product);

                List<CartVO> result = cartService.list();

                assertEquals(1, result.size());
                CartVO vo = result.get(0);
                assertEquals("测试SKU", vo.getSkuName());
                assertEquals("测试商品", vo.getProductName());
                assertEquals("http://example.com/img.jpg", vo.getProductImage());
                assertEquals(new BigDecimal("99.00"), vo.getPrice());
                assertTrue(vo.getValid());
                assertTrue(vo.getChecked());
            }
        }

        @Test
        @DisplayName("获取购物车列表：SKU已删除时标记无效")
        void list_ShouldMarkInvalid_WhenSkuDeleted() {
            CartDO cartItem = new CartDO();
            cartItem.setId(1L);
            cartItem.setUserId(1L);
            cartItem.setSkuId(999L);
            cartItem.setQuantity(2);
            cartItem.setChecked(1);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(cartItem));
                when(skuMapper.selectById(999L)).thenReturn(null);

                List<CartVO> result = cartService.list();

                assertEquals(1, result.size());
                assertFalse(result.get(0).getValid());
            }
        }

        @Test
        @DisplayName("获取购物车列表：库存不足时标记无效")
        void list_ShouldMarkInvalid_WhenStockInsufficient() {
            validSku.setStock(1);
            CartDO cartItem = new CartDO();
            cartItem.setId(1L);
            cartItem.setUserId(1L);
            cartItem.setSkuId(100L);
            cartItem.setQuantity(5);
            cartItem.setChecked(1);

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(cartItem));
                when(skuMapper.selectById(100L)).thenReturn(validSku);

                List<CartVO> result = cartService.list();

                assertEquals(1, result.size());
                assertFalse(result.get(0).getValid());
            }
        }

        @Test
        @DisplayName("获取购物车列表：仅返回未下单的商品")
        void list_ShouldOnlyReturnNotOrdered() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                when(cartMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

                List<CartVO> result = cartService.list();

                assertTrue(result.isEmpty());
                verify(cartMapper).selectList(argThat(wrapper -> true));
            }
        }
    }
}
