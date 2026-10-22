package com.shop.service.impl;

import com.shop.common.BusinessException;
import com.shop.common.MyBatisPlusTestSupport;
import com.shop.common.SaTokenMockUtil;
import com.shop.constant.OrderConstants;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.ReviewMapper;
import com.shop.mapper.UserMapper;
import com.shop.model.dto.ReviewCreateDTO;
import com.shop.model.entity.OrderDO;
import com.shop.model.entity.OrderItemDO;
import com.shop.model.entity.ReviewDO;
import com.shop.model.vo.ReviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品评价服务测试（纯 Mockito）
 * <p>
 * 锁定评价的四道校验：订单项存在、订单归属、订单已收货、订单项未重复评价；
 * 以及删除必须走物理删除（否则订单项无法再次评价）。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewServiceImpl - 评价资格与归属校验")
class ReviewServiceImplTest {

    private static final long USER_ID = 1L;
    private static final long ORDER_ITEM_ID = 300L;
    private static final long ORDER_ID = 200L;

    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private UserMapper userMapper;

    private ReviewServiceImpl service;
    private SaTokenMockUtil.SaTokenLoginScope loginScope;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        MyBatisPlusTestSupport.initTableInfo(ReviewDO.class, OrderDO.class, OrderItemDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new ReviewServiceImpl(reviewMapper, orderMapper, orderItemMapper, userMapper);
        loginScope = SaTokenMockUtil.userLogin(USER_ID);
    }

    @AfterEach
    void tearDown() {
        loginScope.close();
    }

    private static ReviewCreateDTO dto() {
        ReviewCreateDTO dto = new ReviewCreateDTO();
        dto.setOrderItemId(ORDER_ITEM_ID);
        dto.setRating(5);
        dto.setContent("很好用");
        return dto;
    }

    private static OrderItemDO item() {
        OrderItemDO item = new OrderItemDO();
        item.setId(ORDER_ITEM_ID);
        item.setOrderId(ORDER_ID);
        item.setProductId(88L);
        item.setSkuId(99L);
        item.setProductName("松下剃须刀 ES-ST29");
        item.setProductMainImage("/uploads/mock/product/279-0.svg");
        return item;
    }

    private static OrderDO order(long userId, int status) {
        OrderDO order = new OrderDO();
        order.setId(ORDER_ID);
        order.setUserId(userId);
        order.setStatus(status);
        return order;
    }

    @Test
    @DisplayName("订单项不存在：拒绝评价")
    void createMissingItem_throws() {
        when(orderItemMapper.selectById(ORDER_ITEM_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto()));
        assertEquals("订单项不存在", ex.getMessage());
    }

    @Test
    @DisplayName("订单属于他人：按「不存在」处理，不泄露存在性")
    void createForeignOrder_throws() {
        when(orderItemMapper.selectById(ORDER_ITEM_ID)).thenReturn(item());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(999L, OrderConstants.STATUS_RECEIVED));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto()));
        assertEquals("订单项不存在", ex.getMessage());
        verify(reviewMapper, never()).insert(any(ReviewDO.class));
    }

    @Test
    @DisplayName("订单未收货：拒绝评价")
    void createBeforeReceive_throws() {
        when(orderItemMapper.selectById(ORDER_ITEM_ID)).thenReturn(item());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(USER_ID, OrderConstants.STATUS_PAID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto()));
        assertEquals("订单尚未确认收货，暂不能评价", ex.getMessage());
        verify(reviewMapper, never()).insert(any(ReviewDO.class));
    }

    @Test
    @DisplayName("重复评价：拒绝")
    void createDuplicate_throws() {
        when(orderItemMapper.selectById(ORDER_ITEM_ID)).thenReturn(item());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(USER_ID, OrderConstants.STATUS_RECEIVED));
        when(reviewMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto()));
        assertEquals("该商品已评价，请勿重复评价", ex.getMessage());
        verify(reviewMapper, never()).insert(any(ReviewDO.class));
    }

    @Test
    @DisplayName("正常评价：写入商品快照与当前用户")
    void createSuccess_usesOrderSnapshot() {
        when(orderItemMapper.selectById(ORDER_ITEM_ID)).thenReturn(item());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(USER_ID, OrderConstants.STATUS_RECEIVED));
        when(reviewMapper.selectCount(any())).thenReturn(0L);

        service.create(dto());

        ArgumentCaptor<ReviewDO> captor = ArgumentCaptor.forClass(ReviewDO.class);
        verify(reviewMapper).insert(captor.capture());
        ReviewDO saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(88L, saved.getProductId());
        assertEquals("松下剃须刀 ES-ST29", saved.getProductName());
        assertEquals("/uploads/mock/product/279-0.svg", saved.getProductImage());
        assertEquals(1, saved.getStatus(), "新评价默认显示");
    }

    @Test
    @DisplayName("删除评价：走物理删除以释放订单项唯一键")
    void delete_usesHardDelete() {
        ReviewDO review = new ReviewDO();
        review.setId(1L);
        when(reviewMapper.selectById(1L)).thenReturn(review);

        service.delete(1L);

        // 逻辑删除会继续占用 uk_order_item，导致该订单项无法再次评价
        verify(reviewMapper).hardDeleteById(1L);
        verify(reviewMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("匿名评价：列表昵称替换为「匿名用户」")
    void myReviews_masksAnonymousNickname() {
        ReviewDO anonymous = new ReviewDO();
        anonymous.setId(1L);
        anonymous.setUserId(USER_ID);
        anonymous.setIsAnonymous(1);
        anonymous.setRating(5);
        when(reviewMapper.selectList(any())).thenReturn(List.of(anonymous));

        List<ReviewVO> result = service.myReviews();

        assertEquals(1, result.size());
        assertTrue(result.get(0).getAnonymous());
        assertEquals("匿名用户", result.get(0).getNickname());
    }
}
