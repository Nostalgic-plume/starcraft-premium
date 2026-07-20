package com.shop.service.impl;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.common.PaginationHelper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CategoryMapper;
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
import com.shop.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品服务实现
 * 管理商品 SPU 及 SKU，支持价格区间计算、分页查询、分类联动
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final ShopMapper shopMapper;
    private final ProductIndexer productIndexer;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addProduct(ProductDTO dto) {
        CategoryDO category = categoryMapper.selectById(dto.getCategoryId());
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        // 校验：商品只能挂到二级分类（叶子分类）下
        if (category.getParentId() == null || category.getParentId() == 0) {
            throw new BusinessException("请选择具体的子分类（如「手机」而非「手机数码」）");
        }

        ProductDO product = new ProductDO();
        BeanUtil.copyProperties(dto, product);
        product.setImages(dto.getImages() != null ? JSONUtil.toJsonStr(dto.getImages()) : null);
        product.setStatus(ProductConstants.STATUS_ON_SHELF);

        // 自动填充分类层级
        product.setCategoryId(category.getId());
        product.setFirstCategoryId(category.getParentId());

        // 自动填充商家ID（从 shop 表查询，避免 user.id 与 shop.id 混淆）
        try {
            long loginId = StpUtil.getLoginIdAsLong();
            List<String> roles = StpUtil.getRoleList();
            if (roles.contains("merchant")) {
                ShopDO shop = shopMapper.selectOne(
                        new LambdaQueryWrapper<ShopDO>().eq(ShopDO::getUserId, loginId));
                if (shop != null) {
                    product.setShopId(shop.getId());
                }
            }
        } catch (NotLoginException e) {
            // 未登录场景（管理员新增商品时忽略）
        }

        productMapper.insert(product);
        log.info("商品新增成功, productId: {}, shopId: {}", product.getId(), product.getShopId());

        // 同步 RAG 索引
        try {
            productIndexer.addOrUpdate(product);
        } catch (Exception e) {
            log.warn("RAG 索引同步异常（不影响主流程）: {}", e.getMessage());
        }

        if (CollUtil.isNotEmpty(dto.getSkuList())) {
            for (ProductDTO.SkuDTO skuDTO : dto.getSkuList()) {
                SkuDO sku = new SkuDO();
                BeanUtil.copyProperties(skuDTO, sku);
                sku.setProductId(product.getId());
                skuMapper.insert(sku);
            }
        }

        return product.getId();
    }

    @Override
    public PageResult<ProductVO> page(ProductPageQuery query) {
        int[] pn = PaginationHelper.normalize(query.getPage(), query.getPageSize());
        query.setPage(pn[0]);
        query.setPageSize(pn[1]);

        Page<ProductDO> mpPage = new Page<>(query.getPage(), query.getPageSize());
        LambdaQueryWrapper<ProductDO> wrapper = new LambdaQueryWrapper<ProductDO>()
                .orderByDesc(ProductDO::getCreateTime);

        // 一级分类筛选：查询该一级分类下所有二级分类的商品
        if (query.getFirstCategoryId() != null) {
            // 找出所有属于该一级分类的二级分类ID
            List<CategoryDO> subCategories = categoryMapper.selectList(
                    new LambdaQueryWrapper<CategoryDO>()
                            .eq(CategoryDO::getParentId, query.getFirstCategoryId())
                            .eq(CategoryDO::getDeleted, 0));
            List<Long> subIds = subCategories.stream().map(CategoryDO::getId).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(subIds)) {
                wrapper.in(ProductDO::getCategoryId, subIds);
            } else {
                // 如果没有二级分类，尝试直接查一级分类关联的商品
                wrapper.eq(ProductDO::getFirstCategoryId, query.getFirstCategoryId());
            }
        }

        // 二级分类筛选
        if (query.getCategoryId() != null) {
            wrapper.eq(ProductDO::getCategoryId, query.getCategoryId());
        }

        // 商家筛选
        if (query.getShopId() != null) {
            wrapper.eq(ProductDO::getShopId, query.getShopId());
        }

        // 关键字搜索
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(ProductDO::getName, query.getKeyword());
        }

        IPage<ProductDO> iPage = productMapper.selectPage(mpPage, wrapper);
        List<ProductVO> voList = iPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());
        return PageResult.of(iPage, voList);
    }

    @Override
    public ProductVO getDetail(Long id) {
        ProductDO product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        return convertToVO(product);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        ProductDO product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        product.setStatus(status);
        productMapper.updateById(product);
        log.info("商品状态更新, productId: {}, status: {}", id, status);

        // 同步 RAG 索引
        try {
            if (status == ProductConstants.STATUS_ON_SHELF) {
                productIndexer.addOrUpdate(product);
            } else {
                productIndexer.remove(id);
            }
        } catch (Exception e) {
            log.warn("RAG 索引同步异常（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 商品 DO 转 VO */
    private ProductVO convertToVO(ProductDO product) {
        ProductVO vo = new ProductVO();
        BeanUtil.copyProperties(product, vo);

        if (product.getImages() != null) {
            vo.setImages(JSONUtil.toList(product.getImages(), String.class));
        }

        CategoryDO category = categoryMapper.selectById(product.getCategoryId());
        if (category != null) {
            vo.setCategoryName(category.getName());
        }

        List<SkuDO> skuList = skuMapper.selectList(
                new LambdaQueryWrapper<SkuDO>().eq(SkuDO::getProductId, product.getId()));

        if (CollUtil.isNotEmpty(skuList)) {
            BigDecimal minPrice = skuList.stream().map(SkuDO::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal maxPrice = skuList.stream().map(SkuDO::getPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            vo.setMinPrice(minPrice);
            vo.setMaxPrice(maxPrice);

            int totalStock = skuList.stream().mapToInt(SkuDO::getStock).sum();
            vo.setTotalStock(totalStock);

            List<ProductVO.SkuVO> skuVOList = skuList.stream().map(sku -> {
                ProductVO.SkuVO skuVO = new ProductVO.SkuVO();
                BeanUtil.copyProperties(sku, skuVO);
                return skuVO;
            }).collect(Collectors.toList());
            vo.setSkuList(skuVOList);
        }

        return vo;
    }
}
