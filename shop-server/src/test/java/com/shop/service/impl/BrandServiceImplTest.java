package com.shop.service.impl;

import com.shop.common.BusinessException;
import com.shop.common.MyBatisPlusTestSupport;
import com.shop.mapper.BrandMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.dto.BrandDTO;
import com.shop.model.entity.BrandDO;
import com.shop.model.entity.ProductDO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 品牌服务测试（纯 Mockito）
 * <p>
 * 锁定名称唯一与删除保护；`DuplicateKeyException` 要转成可读的业务异常
 * （逻辑删除的旧品牌仍占唯一索引，查重查不到但插入会冲突）。
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrandServiceImpl - 名称唯一与删除保护")
class BrandServiceImplTest {

    @Mock
    private BrandMapper brandMapper;
    @Mock
    private ProductMapper productMapper;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        MyBatisPlusTestSupport.initTableInfo(BrandDO.class, ProductDO.class);
    }

    private BrandServiceImpl service() {
        return new BrandServiceImpl(brandMapper, productMapper);
    }

    private static BrandDTO dto(String name) {
        BrandDTO dto = new BrandDTO();
        dto.setName(name);
        return dto;
    }

    @Test
    @DisplayName("名称重复：拒绝新增")
    void addDuplicateName_throws() {
        when(brandMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().add(dto("华为")));
        assertEquals("品牌名称已存在", ex.getMessage());
        verify(brandMapper, never()).insert(any(BrandDO.class));
    }

    @Test
    @DisplayName("唯一索引冲突（已逻辑删除品牌同名）：转成业务异常")
    void addDuplicateKey_mapsToBusinessException() {
        when(brandMapper.selectCount(any())).thenReturn(0L);
        when(brandMapper.insert(any(BrandDO.class))).thenThrow(new DuplicateKeyException("uk_brand_name"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().add(dto("华为")));
        assertEquals("品牌名称已存在（可能与已删除品牌重名）", ex.getMessage());
    }

    @Test
    @DisplayName("删除有商品引用的品牌：拒绝")
    void deleteWithProducts_throws() {
        BrandDO brand = new BrandDO();
        brand.setId(1L);
        when(brandMapper.selectById(1L)).thenReturn(brand);
        when(productMapper.selectCount(any())).thenReturn(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().delete(1L));
        assertEquals("该品牌下还有 7 个商品，不能删除", ex.getMessage());
        verify(brandMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("品牌不存在：删除时报错")
    void deleteMissing_throws() {
        when(brandMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().delete(1L));
        assertEquals("品牌不存在", ex.getMessage());
    }
}
