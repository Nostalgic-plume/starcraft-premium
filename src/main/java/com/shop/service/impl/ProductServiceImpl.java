package com.shop.service.impl;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.common.StpAdminUtil;
import com.shop.common.StpShopUtil;
import com.shop.common.PaginationHelper;
import com.shop.common.RedisCache;
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
import com.shop.service.ProductService;
import com.shop.service.StockCacheService;
import com.google.common.hash.BloomFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品服务实现
 * 管理商品 SPU 及 SKU，支持价格区间计算、分页查询、分类联动
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final ShopMapper shopMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductIndexer productIndexer;
    private final StockCacheService stockCacheService;
    private final BloomFilter<Long> productBloomFilter;
    private final RedisCache redisCache;

    /**
     * 新增商品（SPU+SKU）：校验分类存在且必须为二级（叶子）分类，自动填充分类层级与商家ID（merchant 角色登录时），
     * 入库后同步布隆过滤器与 RAG 索引，再批量插入 SKU 列表
     *
     * @param dto 商品参数（categoryId、name、images、skuList 等）
     * @return 新商品ID
     * @throws BusinessException 分类不存在、请选择具体的子分类（如「手机」而非「手机数码」）
     */
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

        // 自动填充商家ID（商家登录主体就是 shop_id，直接取 loginId）
        try {
            // 兼容两端：商家（shop）自动绑定本店，管理员（admin）新增不绑店
            long loginId;
            List<String> roles;
            if (StpShopUtil.INSTANCE.isLogin()) {
                loginId = StpShopUtil.INSTANCE.getLoginIdAsLong();
                roles = List.of("merchant");
            } else if (StpAdminUtil.INSTANCE.isLogin()) {
                loginId = StpAdminUtil.INSTANCE.getLoginIdAsLong();
                roles = List.of("admin");
            } else {
                loginId = 0L;
                roles = List.of();
            }
            if (roles.contains("merchant")) {
                ShopDO shop = shopMapper.selectById(loginId);
                if (shop != null) {
                    product.setShopId(shop.getId());
                }
            }
        } catch (NotLoginException e) {
            // 未登录场景（管理员新增商品时忽略）
        }

        productMapper.insert(product);
        log.info("商品新增成功, productId: {}, shopId: {}", product.getId(), product.getShopId());

        // 同步布隆过滤器：新商品 id 必须 put，否则会被 getDetail 的布隆判断误判为"商品不存在"
        productBloomFilter.put(product.getId());

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

    /**
     * 商品分页查询：按创建时间倒序，支持一级/二级分类联动筛选、商家筛选、关键字模糊搜索
     *
     * @param query 分页与筛选参数（page/pageSize、firstCategoryId、categoryId、shopId、keyword）
     * @return 商品分页结果 {@code PageResult<ProductVO>}
     */
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

    /**
     * 商品详情查询：Guava 布隆过滤器前置拦截不存在 ID 防穿透，再走逻辑过期缓存（防击穿，互斥锁异步重建），
     * 缓存未命中回源 DB
     *
     * @param id 商品ID，必填
     * @return 商品详情 VO（含价格区间、SKU 列表、销量、分类名）
     * @throws BusinessException 商品不存在（布隆判定未入库或 DB 查无记录）
     */
    @Override
    public ProductVO getDetail(Long id) {
        // 布隆过滤器前置拦截：Guava 布隆无假阴性，这里为 false 说明该 id 从未入库，
        // 直接短路返回，避免对不存在商品的恶意请求反复穿透缓存打到 DB
        if (!productBloomFilter.mightContain(id)) {
            throw new BusinessException("商品不存在");
        }

        String cacheKey = ProductConstants.CACHE_PRODUCT + id;
        // 逻辑过期缓存（防击穿）：物理永不过期，value 内置过期时间戳；逻辑过期瞬间返回旧数据并异步重建，
        // 热点 key 击穿时始终只有拿到互斥锁的一个线程回源 DB（缓存不存在时的首次重建同样走互斥锁）
        return redisCache.queryWithLogicalExpire(
                cacheKey, ProductVO.class, ProductConstants.CACHE_PRODUCT_LOGICAL_TTL,
                () -> {
                    ProductDO product = productMapper.selectById(id);
                    if (product == null) {
                        throw new BusinessException("商品不存在");
                    }
                    return convertToVO(product);
                });
    }

    /**
     * 更新商品状态：修改后主动删除商品详情缓存保证变更立即可见，并按状态同步 RAG 索引
     * （上架则新增/更新索引，否则从索引移除）
     *
     * @param id     商品ID，必填
     * @param status 新状态：0=待审核 1=上架 2=下架
     * @throws BusinessException 商品不存在
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        ProductDO product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        product.setStatus(status);
        productMapper.updateById(product);
        redisCache.delete(ProductConstants.CACHE_PRODUCT + id);
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

    /**
     * 商家调整 SKU 库存：更新 MySQL 库存后按 Cache Aside 删除库存缓存与商品详情缓存，
     * 下次预扣（Lua miss 回源）自动加载新值，保证 Redis 与 DB 一致；归属校验 shop_id 隔离
     *
     * @param skuId SKU ID
     * @param stock 新库存（>=0）
     * @throws BusinessException SKU 不存在、无权操作该商品、库存为负
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSkuStock(Long skuId, Integer stock) {
        if (stock == null || stock < 0) {
            throw new BusinessException("库存不能为负");
        }
        SkuDO sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException("SKU 不存在");
        }
        // 归属校验：当前登录商家只能调整自己商品的 SKU 库存（shop_id 数据隔离）
        long shopId = StpShopUtil.INSTANCE.getLoginIdAsLong();
        ProductDO product = productMapper.selectById(sku.getProductId());
        if (product == null || !Objects.equals(product.getShopId(), shopId)) {
            throw new BusinessException("无权操作该商品");
        }
        // 更新 MySQL 库存（最终凭证）
        skuMapper.update(null, new LambdaUpdateWrapper<SkuDO>()
                .eq(SkuDO::getId, skuId)
                .set(SkuDO::getStock, stock));
        log.info("商家调整 SKU 库存: skuId={}, stock={}, shopId={}", skuId, stock, shopId);

        // Cache Aside：删除库存缓存，下次预扣 miss 自动从 DB 加载新值，避免旧缓存被继续扣减
        stockCacheService.evictStock(skuId);
        // 淘汰商品详情缓存（详情页含 SKU 库存，主动失效保证立即可见）
        redisCache.delete(ProductConstants.CACHE_PRODUCT + product.getId());
    }

    /**
     * 商家端商品分页：shop_id 取自当前登录商家并注入查询条件后复用公共分页逻辑（数据隔离）
     *
     * @param query 分页与筛选参数（page/pageSize 等，shopId 由内部覆盖为当前商家）
     * @return 本店商品分页结果 {@code PageResult<ProductVO>}
     */
    @Override
    public PageResult<ProductVO> merchantPage(ProductPageQuery query) {
        query.setShopId(StpShopUtil.INSTANCE.getLoginIdAsLong());
        return page(query);
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

        // SKU 库存预热到 Redis（供 Lua 预扣使用，setIfAbsent 只写一次，不影响浏览性能）
        if (CollUtil.isNotEmpty(skuList)) {
            skuList.forEach(sku -> stockCacheService.initStock(sku.getId(), sku.getStock()));
        }

        Integer sold = orderItemMapper.sumQuantityByProductId(product.getId());
        vo.setSoldCount(sold != null ? sold : 0);

        return vo;
    }
}
