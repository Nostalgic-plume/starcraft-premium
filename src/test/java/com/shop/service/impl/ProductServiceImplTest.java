package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.ShopMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.dto.ProductDTO;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.entity.ShopDO;
import com.shop.model.entity.SkuDO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import com.shop.rag.ProductIndexer;
import com.shop.service.StockCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
 * ProductService 单元测试
 * 覆盖商品 CRUD、分页查询、状态更新及 VO 转换
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService - 商品服务")
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private SkuMapper skuMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private ProductIndexer productIndexer;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private StockCacheService stockCacheService;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private ProductServiceImpl productService;

    private ProductDO product;
    private ProductDTO.SkuDTO skuDTO;
    private ProductDTO productDTO;
    private CategoryDO leafCategory;
    private CategoryDO parentCategory;
    private SkuDO sku;

    @BeforeEach
    void setUp() {
        parentCategory = new CategoryDO();
        parentCategory.setId(1L);
        parentCategory.setName("手机数码");
        parentCategory.setParentId(0L);

        leafCategory = new CategoryDO();
        leafCategory.setId(10L);
        leafCategory.setName("手机");
        leafCategory.setParentId(1L);

        skuDTO = new ProductDTO.SkuDTO();
        skuDTO.setName("测试SKU");
        skuDTO.setPrice(new BigDecimal("99.00"));
        skuDTO.setStock(100);

        productDTO = new ProductDTO();
        productDTO.setName("测试商品");
        productDTO.setCategoryId(10L);
        productDTO.setImages(List.of("http://example.com/img1.jpg"));
        productDTO.setSkuList(List.of(skuDTO));

        product = new ProductDO();
        product.setId(100L);
        product.setName("测试商品");
        product.setCategoryId(10L);
        product.setFirstCategoryId(1L);
        product.setShopId(50L);
        product.setStatus(ProductConstants.STATUS_ON_SHELF);

        sku = new SkuDO();
        sku.setId(1000L);
        sku.setProductId(100L);
        sku.setName("测试SKU");
        sku.setPrice(new BigDecimal("99.00"));
        sku.setStock(100);
    }

    // ==================== 新增商品 ====================

    @Nested
    @DisplayName("新增商品 - addProduct()")
    class AddProductTest {

        @Test
        @DisplayName("成功新增商品：包含SKU列表")
        void addProduct_ShouldSucceed_WithSkus() {
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            doAnswer(inv -> { inv.<ProductDO>getArgument(0).setId(100L); return 0; })
                    .when(productMapper).insert(any(ProductDO.class));

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                stpUtil.when(StpUtil::getRoleList).thenReturn(List.of("merchant"));
                when(shopMapper.selectById(1L)).thenAnswer(inv -> {
                    ShopDO shop = new ShopDO();
                    shop.setId(50L);
                    return shop;
                });

                Long productId = productService.addProduct(productDTO);

                assertEquals(100L, productId);
                verify(productMapper).insert(argThat(p ->
                        p.getFirstCategoryId() == 1L && p.getShopId() == 50L));
                verify(skuMapper).insert(argThat(s ->
                        s.getProductId() == 100L && s.getName().equals("测试SKU")));
            }
        }

        @Test
        @DisplayName("新增失败：分类不存在")
        void addProduct_ShouldThrow_WhenCategoryNotFound() {
            when(categoryMapper.selectById(10L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> productService.addProduct(productDTO));
            assertEquals("分类不存在", ex.getMessage());
            verify(productMapper, never()).insert(any());
        }

        @Test
        @DisplayName("新增失败：选择的不是叶子分类")
        void addProduct_ShouldThrow_WhenNotLeafCategory() {
            when(categoryMapper.selectById(1L)).thenReturn(parentCategory);
            productDTO.setCategoryId(1L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> productService.addProduct(productDTO));
            assertTrue(ex.getMessage().contains("请选择具体的子分类"));
            verify(productMapper, never()).insert(any());
        }

        @Test
        @DisplayName("新增成功：非商家角色不填充 shopId")
        void addProduct_ShouldNotSetShopId_WhenNotMerchant() {
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            doAnswer(inv -> { inv.<ProductDO>getArgument(0).setId(100L); return 0; })
                    .when(productMapper).insert(any(ProductDO.class));

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                stpUtil.when(StpUtil::getRoleList).thenReturn(List.of("user")); // 非商家

                productService.addProduct(productDTO);

                verify(productMapper).insert(argThat(p -> p.getShopId() == null));
            }
        }

        @Test
        @DisplayName("新增成功：SKU 列表为空时也能保存")
        void addProduct_ShouldSucceed_WhenSkuListEmpty() {
            productDTO.setSkuList(List.of());
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            doAnswer(inv -> { inv.<ProductDO>getArgument(0).setId(100L); return 0; })
                    .when(productMapper).insert(any(ProductDO.class));

            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                stpUtil.when(StpUtil::getRoleList).thenReturn(List.of("merchant"));
                when(shopMapper.selectById(1L)).thenReturn(null);

                Long productId = productService.addProduct(productDTO);

                assertEquals(100L, productId);
                verify(skuMapper, never()).insert(any());
            }
        }
    }

    // ==================== 分页查询 ====================

    @Nested
    @DisplayName("分页查询 - page()")
    class PageTest {

        @Test
        @DisplayName("无条件查询：返回所有商品")
        void page_ShouldReturnAll() {
            ProductPageQuery query = new ProductPageQuery();
            Page<ProductDO> mpPage = new Page<>(1, 10);
            mpPage.setRecords(List.of(product));
            mpPage.setTotal(1);

            when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mpPage);
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            when(skuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sku));

            PageResult<ProductVO> result = productService.page(query);

            assertEquals(1, result.getTotal());
            assertEquals("测试商品", result.getList().get(0).getName());
        }

        @Test
        @DisplayName("按一级分类查询：找到二级分类")
        void page_ShouldFilterByFirstCategory() {
            ProductPageQuery query = new ProductPageQuery();
            query.setFirstCategoryId(1L);

            Page<ProductDO> mpPage = new Page<>(1, 10);
            mpPage.setRecords(List.of(product));
            mpPage.setTotal(1);

            // 返回子分类
            when(categoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(leafCategory));
            when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mpPage);
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            when(skuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sku));

            PageResult<ProductVO> result = productService.page(query);

            assertEquals(1, result.getTotal());
        }

        @Test
        @DisplayName("按一级分类查询：没有二级分类时使用 firstCategoryId")
        void page_ShouldUseFirstCategoryId_WhenNoSubCategories() {
            ProductPageQuery query = new ProductPageQuery();
            query.setFirstCategoryId(1L);

            Page<ProductDO> mpPage = new Page<>(1, 10);
            mpPage.setRecords(List.of(product));
            mpPage.setTotal(1);

            when(categoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mpPage);
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            when(skuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sku));

            PageResult<ProductVO> result = productService.page(query);

            assertEquals(1, result.getTotal());
        }

        @Test
        @DisplayName("按关键字搜索")
        void page_ShouldFilterByKeyword() {
            ProductPageQuery query = new ProductPageQuery();
            query.setKeyword("手机");

            Page<ProductDO> mpPage = new Page<>(1, 10);
            mpPage.setRecords(List.of(product));
            mpPage.setTotal(1);

            when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mpPage);
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            when(skuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sku));

            PageResult<ProductVO> result = productService.page(query);

            assertEquals(1, result.getTotal());
        }

        @Test
        @DisplayName("查询结果为空")
        void page_ShouldReturnEmpty_WhenNoResults() {
            ProductPageQuery query = new ProductPageQuery();
            Page<ProductDO> mpPage = new Page<>(1, 10);
            mpPage.setRecords(List.of());
            mpPage.setTotal(0);

            when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mpPage);

            PageResult<ProductVO> result = productService.page(query);

            assertEquals(0, result.getTotal());
            assertTrue(result.getList().isEmpty());
        }
    }

    // ==================== 商品详情 ====================

    @Nested
    @DisplayName("商品详情 - getDetail()")
    class GetDetailTest {

        @Test
        @DisplayName("成功获取详情，含 SKU 列表和分类名")
        void getDetail_ShouldReturnVO() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // 缓存 miss，走 DB
            when(productMapper.selectById(100L)).thenReturn(product);
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            when(skuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sku));

            ProductVO vo = productService.getDetail(100L);

            assertNotNull(vo);
            assertEquals("测试商品", vo.getName());
            assertEquals("手机", vo.getCategoryName());
            assertEquals(new BigDecimal("99.00"), vo.getMinPrice());
            assertEquals(new BigDecimal("99.00"), vo.getMaxPrice());
            assertEquals(100, vo.getTotalStock());
            assertEquals(1, vo.getSkuList().size());
        }

        @Test
        @DisplayName("获取详情：商品不存在")
        void getDetail_ShouldThrow_WhenNotFound() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // 缓存 miss，走 DB
            when(productMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> productService.getDetail(99L));
            assertEquals("商品不存在", ex.getMessage());
        }

        @Test
        @DisplayName("获取详情：无 SKU 时价格和库存返回 null")
        void getDetail_ShouldReturnNullValues_WhenNoSkus() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // 缓存 miss，走 DB
            when(productMapper.selectById(100L)).thenReturn(product);
            when(categoryMapper.selectById(10L)).thenReturn(leafCategory);
            when(skuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            ProductVO vo = productService.getDetail(100L);

            assertNull(vo.getMinPrice());
            assertNull(vo.getMaxPrice());
            assertNull(vo.getTotalStock());
            assertNull(vo.getSkuList());
        }
    }

    // ==================== 状态更新 ====================

    @Nested
    @DisplayName("状态更新 - updateStatus()")
    class UpdateStatusTest {

        @Test
        @DisplayName("成功更新商品状态")
        void updateStatus_ShouldSucceed() {
            when(productMapper.selectById(100L)).thenReturn(product);

            productService.updateStatus(100L, 0);

            verify(productMapper).updateById(argThat(p ->
                    p.getStatus() == 0));
        }

        @Test
        @DisplayName("更新状态：商品不存在")
        void updateStatus_ShouldThrow_WhenNotFound() {
            when(productMapper.selectById(99L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> productService.updateStatus(99L, 0));
            assertEquals("商品不存在", ex.getMessage());
            verify(productMapper, never()).updateById(any());
        }
    }
}
