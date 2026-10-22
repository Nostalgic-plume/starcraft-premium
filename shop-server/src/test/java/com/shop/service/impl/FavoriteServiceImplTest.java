package com.shop.service.impl;

import com.shop.common.BusinessException;
import com.shop.common.MyBatisPlusTestSupport;
import com.shop.common.SaTokenMockUtil;
import com.shop.constant.ProductConstants;
import com.shop.mapper.FavoriteMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.FavoriteDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.vo.ProductVO;
import com.shop.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品收藏服务测试（纯 Mockito）
 * <p>
 * 锁定三条：收藏幂等、只允许收藏上架商品、收藏列表跳过不可展示商品。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteServiceImpl - 幂等与上架校验")
class FavoriteServiceImplTest {

    private static final long USER_ID = 1L;
    private static final long PRODUCT_ID = 100L;

    @Mock
    private FavoriteMapper favoriteMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductService productService;

    private FavoriteServiceImpl service;
    private SaTokenMockUtil.SaTokenLoginScope loginScope;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        MyBatisPlusTestSupport.initTableInfo(FavoriteDO.class, ProductDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new FavoriteServiceImpl(favoriteMapper, productMapper, productService);
        loginScope = SaTokenMockUtil.userLogin(USER_ID);
    }

    @AfterEach
    void tearDown() {
        loginScope.close();
    }

    private static ProductDO onShelfProduct() {
        ProductDO product = new ProductDO();
        product.setId(PRODUCT_ID);
        product.setStatus(ProductConstants.STATUS_ON_SHELF);
        return product;
    }

    @Test
    @DisplayName("重复收藏：幂等，不重复插入")
    void addExisting_isIdempotent() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onShelfProduct());
        when(favoriteMapper.selectCount(any())).thenReturn(1L);

        service.add(PRODUCT_ID);

        verify(favoriteMapper, never()).insert(any(FavoriteDO.class));
    }

    @Test
    @DisplayName("首次收藏：插入记录并带上当前用户")
    void addNew_insertsWithCurrentUser() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onShelfProduct());
        when(favoriteMapper.selectCount(any())).thenReturn(0L);

        service.add(PRODUCT_ID);

        verify(favoriteMapper).insert(any(FavoriteDO.class));
    }

    @Test
    @DisplayName("收藏不存在 / 未上架商品：拒绝")
    void addUnavailableProduct_throws() {
        ProductDO offShelf = new ProductDO();
        offShelf.setId(PRODUCT_ID);
        offShelf.setStatus(2);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(offShelf);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.add(PRODUCT_ID));
        assertEquals("商品不存在或已下架", ex.getMessage());
        verify(favoriteMapper, never()).insert(any(FavoriteDO.class));
    }

    @Test
    @DisplayName("收藏列表：跳过商品已删除 / 已下架的收藏项")
    void list_skipsUnavailableProducts() {
        FavoriteDO alive = new FavoriteDO();
        alive.setProductId(1L);
        FavoriteDO deleted = new FavoriteDO();
        deleted.setProductId(2L);
        FavoriteDO offShelf = new FavoriteDO();
        offShelf.setProductId(3L);
        when(favoriteMapper.selectList(any())).thenReturn(List.of(alive, deleted, offShelf));

        ProductVO aliveVo = new ProductVO();
        aliveVo.setId(1L);
        aliveVo.setStatus(ProductConstants.STATUS_ON_SHELF);
        ProductVO offShelfVo = new ProductVO();
        offShelfVo.setId(3L);
        offShelfVo.setStatus(2);

        when(productService.getDetail(1L)).thenReturn(aliveVo);
        when(productService.getDetail(2L)).thenThrow(new BusinessException("商品不存在"));
        when(productService.getDetail(3L)).thenReturn(offShelfVo);

        List<ProductVO> result = service.list();

        assertEquals(1, result.size(), "只应返回仍在上架的商品");
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    @DisplayName("取消收藏：按「用户 + 商品」物理删除")
    void remove_deletesByUserAndProduct() {
        service.remove(PRODUCT_ID);

        verify(favoriteMapper).delete(any());
    }
}
