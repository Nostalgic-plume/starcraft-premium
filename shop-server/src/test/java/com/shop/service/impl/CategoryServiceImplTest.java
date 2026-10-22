package com.shop.service.impl;

import com.shop.common.BusinessException;
import com.shop.common.MyBatisPlusTestSupport;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.dto.CategoryDTO;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ProductDO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类服务测试（纯 Mockito）
 * <p>
 * 锁定两级结构与删除保护：有子分类 / 有商品引用时不允许删除，
 * 父分类必须是存在的一级分类。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryServiceImpl - 两级结构与删除保护")
class CategoryServiceImplTest {

    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        MyBatisPlusTestSupport.initTableInfo(CategoryDO.class, ProductDO.class);
    }

    private CategoryServiceImpl service() {
        return new CategoryServiceImpl(categoryMapper, productMapper, redisTemplate);
    }

    private static CategoryDTO dto(Long parentId) {
        CategoryDTO dto = new CategoryDTO();
        dto.setName("测试分类");
        dto.setParentId(parentId);
        return dto;
    }

    private static CategoryDO category(Long id, Long parentId) {
        CategoryDO category = new CategoryDO();
        category.setId(id);
        category.setParentId(parentId);
        category.setName("分类" + id);
        return category;
    }

    @Test
    @DisplayName("删除有子分类的分类：拒绝")
    void deleteWithChildren_throws() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, 0L));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().delete(1L));
        assertEquals("该分类下还有子分类，请先删除子分类", ex.getMessage());
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除有商品引用的分类：拒绝")
    void deleteWithProducts_throws() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, 0L));
        // 第一次查子分类=0，第二次查商品=5
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productMapper.selectCount(any())).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().delete(1L));
        assertEquals("该分类下还有 5 个商品，不能删除", ex.getMessage());
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除空分类：成功并清缓存")
    void deleteEmpty_clearsCache() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, 0L));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.deleteById(1L)).thenReturn(1);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of("category:listall"));

        service().delete(1L);

        verify(categoryMapper).deleteById(1L);
        verify(redisTemplate).delete(Set.of("category:listall"));
    }

    @Test
    @DisplayName("新增二级分类但父分类不存在：拒绝")
    void addWithMissingParent_throws() {
        when(categoryMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().add(dto(99L)));
        assertEquals("父分类不存在", ex.getMessage());
    }

    @Test
    @DisplayName("分类最多两级：父分类本身是二级分类时拒绝")
    void addThirdLevel_throws() {
        // 父分类的 parentId 非 0，说明它已经是二级分类
        when(categoryMapper.selectById(5L)).thenReturn(category(5L, 1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().add(dto(5L)));
        assertEquals("分类最多两级，父分类必须是一级分类", ex.getMessage());
    }

    @Test
    @DisplayName("新增一级分类：成功并清缓存")
    void addRoot_success() {
        when(categoryMapper.insert(any(CategoryDO.class))).thenAnswer(inv -> {
            ((CategoryDO) inv.getArgument(0)).setId(10L);
            return 1;
        });
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());

        Long id = service().add(dto(0L));

        assertEquals(10L, id);
        verify(categoryMapper).insert(any(CategoryDO.class));
    }

    @Test
    @DisplayName("有子分类的一级分类不能改成二级")
    void demoteWithChildren_throws() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, 0L));
        when(categoryMapper.selectById(2L)).thenReturn(category(2L, 0L));
        when(categoryMapper.selectCount(any())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().update(1L, dto(2L)));
        assertEquals("该分类下还有子分类，不能改为二级分类", ex.getMessage());
    }
}
