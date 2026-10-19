package com.shop.service.impl;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
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
import com.shop.mapper.BrandMapper;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.ShopMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.dto.ProductDTO;
import com.shop.model.dto.ProductSalesDTO;
import com.shop.model.entity.BrandDO;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.entity.ShopDO;
import com.shop.model.entity.SkuDO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import com.shop.rag.index.ProductIndexer;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final BrandMapper brandMapper;
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

        // 校验品牌存在（品牌本身可选，但传了就必须有效）：前端下拉已限制取值，
        // 这里防的是直接调 API 写入悬空 brand_id —— 否则该商品品牌位恒为空且无从排查
        if (dto.getBrandId() != null && brandMapper.selectById(dto.getBrandId()) == null) {
            throw new BusinessException("品牌不存在");
        }

        ProductDO product = new ProductDO();
        BeanUtil.copyProperties(dto, product);
        product.setImages(dto.getImages() != null ? JSONUtil.toJsonStr(dto.getImages()) : null);
        // 初始状态：管理员新增即上架（管理员本身就是审核方，无需自我审核）；
        // 商家新增一律进待审核，须经管理员审核通过才上架
        product.setStatus(StpAdminUtil.INSTANCE.isLogin()
                ? ProductConstants.STATUS_ON_SHELF
                : ProductConstants.STATUS_PENDING);

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

        if (CollUtil.isNotEmpty(dto.getSkuList())) {
            for (ProductDTO.SkuDTO skuDTO : dto.getSkuList()) {
                SkuDO sku = new SkuDO();
                BeanUtil.copyProperties(skuDTO, sku);
                sku.setProductId(product.getId());
                skuMapper.insert(sku);
            }
        }

        // 同步 RAG 索引 —— ⚠️ 顺序不可调换：必须晚于 SKU 落库。
        // 索引构建要读 SKU 价格区间（SkuPriceResolver.resolveOne），若在此之前调用则价格查不到 →
        // payload 里没有 minPrice/maxPrice → 该商品在价格区间筛选时**永远不会被淘汰**
        // （passMetadataFilter 对缺失键按「不过滤」放行）。
        // 影响面：管理员新增的商品 status=1，会真正入索引，缺陷静默生效（无异常无日志）；
        // 商家新增的 status=0 被 addOrUpdate 首行拦掉，属无害的无用调用。
        try {
            productIndexer.addOrUpdate(product);
        } catch (Exception e) {
            log.warn("RAG 索引同步异常（不影响主流程）: {}", e.getMessage());
        }

        return product.getId();
    }

    /**
     * 编辑商品（SPU + SKU）：更新商品基本信息，并按 id 对 SKU 列表做「增 / 改 / 删」diff
     * <p>
     * 归属校验：商家登录时只能编辑本店商品（shop_id 隔离），管理员登录时放行。
     * 变更后主动失效商品详情缓存并同步 RAG 索引；SKU 库存有变动时额外失效库存缓存。
     * </p>
     * <p>
     * 字段更新采用「显式逐字段赋值」而非 {@code BeanUtil.copyProperties}——
     * 后者会把 status、deleted 等不该由编辑接口触碰的字段一并覆盖。
     * </p>
     *
     * @param id  商品 ID
     * @param dto 商品信息（SKU 列表为完整列表）
     * @throws BusinessException 商品不存在、无权操作该商品、分类不存在或非叶子分类、品牌不存在、SKU 不属于该商品
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProduct(Long id, ProductDTO dto) {
        ProductDO product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }

        // 归属校验：商家只能改本店商品；管理员走 admin 登录态，此处不拦
        if (StpShopUtil.INSTANCE.isLogin()) {
            long shopId = StpShopUtil.INSTANCE.getLoginIdAsLong();
            if (!Objects.equals(product.getShopId(), shopId)) {
                throw new BusinessException("无权操作该商品");
            }
        }

        // 分类校验：必须挂到二级（叶子）分类，并重算一级分类，避免沿用旧值导致分类联动错乱
        CategoryDO category = categoryMapper.selectById(dto.getCategoryId());
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        if (category.getParentId() == null || category.getParentId() == 0) {
            throw new BusinessException("请选择具体的子分类（如「手机」而非「手机数码」）");
        }

        // 品牌可选，但传了就必须有效（防悬空 brand_id）
        if (dto.getBrandId() != null && brandMapper.selectById(dto.getBrandId()) == null) {
            throw new BusinessException("品牌不存在");
        }

        // 逐字段赋值；images 序列化为 JSON 字符串存储（与新增逻辑保持一致）
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setCategoryId(category.getId());
        product.setFirstCategoryId(category.getParentId());
        product.setBrandId(dto.getBrandId());
        product.setMainImage(dto.getMainImage());
        product.setImages(dto.getImages() != null ? JSONUtil.toJsonStr(dto.getImages()) : null);
        product.setDetail(dto.getDetail());
        productMapper.updateById(product);

        syncSkus(product.getId(), dto.getSkuList());

        // 主动失效详情缓存：逻辑过期缓存物理永不过期，不删除则用户端一直看到旧数据
        redisCache.delete(ProductConstants.CACHE_PRODUCT + id);

        // 同步 RAG 索引（失败只告警，不阻断主流程）
        try {
            if (product.getStatus() != null && product.getStatus() == ProductConstants.STATUS_ON_SHELF) {
                productIndexer.addOrUpdate(product);
            } else {
                productIndexer.remove(id);
            }
        } catch (Exception e) {
            log.warn("RAG 索引同步异常（不影响主流程）: {}", e.getMessage());
        }

        log.info("商品编辑成功, productId: {}, shopId: {}", id, product.getShopId());
    }

    /**
     * 按 id 对商品 SKU 列表做 diff 同步
     * <p>
     * 三态：带 id 且属于本商品 → 更新；不带 id → 新增；库中存在但提交列表未含 → 逻辑删除。
     * 库存发生变化的 SKU 额外失效 Redis 库存缓存，避免旧缓存被继续扣减造成超卖。
     * </p>
     *
     * @param productId  商品 ID
     * @param skuDTOList 前端提交的完整 SKU 列表（已通过 {@code @NotEmpty} 校验）
     * @throws BusinessException 传入的 SKU id 不属于该商品
     */
    private void syncSkus(Long productId, List<ProductDTO.SkuDTO> skuDTOList) {
        List<SkuDO> existing = skuMapper.selectList(
                new LambdaQueryWrapper<SkuDO>().eq(SkuDO::getProductId, productId));
        Map<Long, SkuDO> existingMap = existing.stream()
                .collect(Collectors.toMap(SkuDO::getId, s -> s));

        Set<Long> keepIds = new HashSet<>();
        for (ProductDTO.SkuDTO skuDTO : skuDTOList) {
            if (skuDTO.getId() != null) {
                SkuDO sku = existingMap.get(skuDTO.getId());
                if (sku == null) {
                    throw new BusinessException("SKU 不属于该商品");
                }
                boolean stockChanged = !Objects.equals(sku.getStock(), skuDTO.getStock());
                sku.setName(skuDTO.getName());
                sku.setSpecs(skuDTO.getSpecs());
                sku.setPrice(skuDTO.getPrice());
                sku.setOriginalPrice(skuDTO.getOriginalPrice());
                sku.setStock(skuDTO.getStock());
                sku.setImage(skuDTO.getImage());
                skuMapper.updateById(sku);
                if (stockChanged) {
                    stockCacheService.evictStock(sku.getId());
                }
                keepIds.add(sku.getId());
            } else {
                SkuDO sku = new SkuDO();
                BeanUtil.copyProperties(skuDTO, sku);
                sku.setId(null);
                sku.setProductId(productId);
                skuMapper.insert(sku);
            }
        }

        // 提交列表中已移除的 SKU：逻辑删除（@TableLogic 生效，保留审计痕迹），并清理其库存缓存
        for (SkuDO sku : existing) {
            if (!keepIds.contains(sku.getId())) {
                skuMapper.deleteById(sku.getId());
                stockCacheService.evictStock(sku.getId());
                log.info("编辑商品时逻辑删除 SKU: productId={}, skuId={}", productId, sku.getId());
            }
        }
    }

    /**
     * 商品分页查询（用户端/公开接口）：强制仅返回上架商品，下架与待审核商品对前台不可见；
     * 管理员登录时放行全状态并支持按 status 筛选（管理后台商品管理复用此接口）
     *
     * @param query 分页与筛选参数（page/pageSize、firstCategoryId、categoryId、shopId、keyword）
     * @return 商品分页结果 {@code PageResult<ProductVO>}
     */
    @Override
    public PageResult<ProductVO> page(ProductPageQuery query) {
        // 管理后台需查看/筛选全状态商品；其余调用方（含未登录）一律只看上架商品
        return doPage(query, isAdminLoginQuietly());
    }

    /**
     * 静默判断管理员登录态：非 Web 上下文（单元测试、后台线程）取不到 Sa-Token 上下文时降级为 false，
     * 即按"用户端"处理——只可见上架商品。宁可少展示，也不因上下文缺失而泄漏下架商品
     *
     * @return true=管理员已登录
     */
    private boolean isAdminLoginQuietly() {
        try {
            return StpAdminUtil.INSTANCE.isLogin();
        } catch (SaTokenException e) {
            log.debug("无 Sa-Token 上下文（非 Web 线程），商品分页按用户端处理：仅上架可见");
            return false;
        }
    }

    /**
     * 商品分页查询内部实现：按创建时间倒序，支持一级/二级分类联动筛选、商家筛选、关键字模糊搜索与状态筛选
     *
     * @param query            分页与筛选参数
     * @param canViewAllStatus true=可查看全状态商品（管理端/商家端），false=强制仅上架（用户端）
     * @return 商品分页结果 {@code PageResult<ProductVO>}
     */
    private PageResult<ProductVO> doPage(ProductPageQuery query, boolean canViewAllStatus) {
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

        // 状态过滤：用户端强制上架（忽略入参，服务端兜底——否则前端传 status=2 即可看到下架商品，
        // 点进去下单才被 OrderServiceImpl 判为"商品已下架"）；管理端/商家端按需筛选
        if (canViewAllStatus) {
            if (query.getStatus() != null) {
                wrapper.eq(ProductDO::getStatus, query.getStatus());
            }
        } else {
            wrapper.eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF);
        }

        IPage<ProductDO> iPage = productMapper.selectPage(mpPage, wrapper);
        // 整页预加载分类/品牌/SKU/销量：convertToVO 逐条查会让单次分页产生 4N 次查询
        // （pageSize 上限 200 → 最多 800 次），故一次性预加载后按 id 取用
        ProductVoLookup lookup = loadLookup(iPage.getRecords());
        List<ProductVO> voList = iPage.getRecords().stream()
                .map(p -> convertToVO(p, lookup))
                .collect(Collectors.toList());
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
        ProductVO vo = redisCache.queryWithLogicalExpire(
                cacheKey, ProductVO.class, ProductConstants.CACHE_PRODUCT_LOGICAL_TTL,
                () -> {
                    ProductDO product = productMapper.selectById(id);
                    if (product == null) {
                        throw new BusinessException("商品不存在");
                    }
                    return convertToVO(product);
                });

        // 待审核商品对外完全不可见（尚未发布过）：仅管理员与商家可查。
        // 判定放在缓存读取之后，故缓存命中/回源 DB 两条路径都被覆盖，不会因缓存绕过。
        // 商家侧另有 ShopDataIsolationInterceptor 追加 shop_id 条件，只能命中本店商品。
        // 注意：下架商品（status=2）不在此列——它曾发布过，详情页允许查看，仅在下单时拦截。
        if (vo.getStatus() != null && vo.getStatus() == ProductConstants.STATUS_PENDING
                && !StpAdminUtil.INSTANCE.isLogin() && !StpShopUtil.INSTANCE.isLogin()) {
            throw new BusinessException("商品不存在");
        }

        return vo;
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
        // 状态值合法性：只允许 0=待审核 / 1=上架 / 2=下架
        if (status == null || (status != ProductConstants.STATUS_PENDING
                && status != ProductConstants.STATUS_ON_SHELF
                && status != ProductConstants.STATUS_OFF_SHELF)) {
            throw new BusinessException("非法的商品状态");
        }

        // 商家不能自行上架：上架须经管理员审核，否则审核流程形同虚设
        // （商家上架只需一次 API 调用即可绕过，故必须在此拦截，前端置灰不构成防护）
        if (StpShopUtil.INSTANCE.isLogin() && status == ProductConstants.STATUS_ON_SHELF) {
            throw new BusinessException("商品上架需管理员审核，请提交审核后等待审核通过");
        }

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
        // 商家端管理本店商品，需可见待审核/下架状态（shop_id 已在上面注入，数据隔离不依赖状态过滤）
        return doPage(query, true);
    }

    /** 商品 DO 转 VO（单条路径：逐条查库） */
    private ProductVO convertToVO(ProductDO product) {
        return convertToVO(product, null);
    }

    /**
     * 商品 DO 转 VO
     * <p>
     * {@code lookup} 为 null 时逐条查库（详情页等单条场景）；分页场景传入整页预加载上下文，
     * 避免每个商品各查 4 次（分类 / 品牌 / SKU / 销量）导致的 4N 次查询。
     *
     * @param product 商品 DO
     * @param lookup  整页预加载上下文；null 表示单条查询
     * @return 商品 VO
     */
    private ProductVO convertToVO(ProductDO product, ProductVoLookup lookup) {
        ProductVO vo = new ProductVO();
        BeanUtil.copyProperties(product, vo);

        if (product.getImages() != null) {
            vo.setImages(JSONUtil.toList(product.getImages(), String.class));
        }

        // 分类名（保持原语义：查不到则不设该字段）
        String categoryName;
        if (lookup != null) {
            categoryName = lookup.categoryNames().get(product.getCategoryId());
        } else {
            CategoryDO category = categoryMapper.selectById(product.getCategoryId());
            categoryName = category != null ? category.getName() : null;
        }
        if (categoryName != null) {
            vo.setCategoryName(categoryName);
        }

        // 品牌名由品牌ID关联品牌主数据表取得（商品表不再冗余存储品牌名）
        if (product.getBrandId() != null) {
            String brandName;
            if (lookup != null) {
                brandName = lookup.brandNames().get(product.getBrandId());
            } else {
                BrandDO brand = brandMapper.selectById(product.getBrandId());
                brandName = brand != null ? brand.getName() : null;
            }
            if (brandName != null) {
                vo.setBrandName(brandName);
            }
        }

        List<SkuDO> skuList;
        if (lookup != null) {
            skuList = lookup.skusByProduct().getOrDefault(product.getId(), List.of());
        } else {
            skuList = skuMapper.selectList(
                    new LambdaQueryWrapper<SkuDO>().eq(SkuDO::getProductId, product.getId()));
        }

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

        Integer sold;
        if (lookup != null) {
            sold = lookup.soldCounts().get(product.getId());
        } else {
            sold = orderItemMapper.sumQuantityByProductId(product.getId());
        }
        vo.setSoldCount(sold != null ? sold : 0);

        return vo;
    }

    /**
     * 商品 VO 转换所需的整页预加载上下文
     * <p>
     * 商品列表每条都要展示分类名 / 品牌名 / 价格区间 / 销量，逐条查库会让单次分页
     * 产生 4N 次查询（pageSize 上限 200 → 最多 800 次）。整页一次性预加载后按 id 取用。
     *
     * @param categoryNames 分类 id → 名称
     * @param brandNames    品牌 id → 名称
     * @param skusByProduct 商品 id → SKU 列表
     * @param soldCounts    商品 id → 累计销量（无销量的商品不在 Map 中，按 0 处理）
     */
    private record ProductVoLookup(Map<Long, String> categoryNames,
                                   Map<Long, String> brandNames,
                                   Map<Long, List<SkuDO>> skusByProduct,
                                   Map<Long, Integer> soldCounts) {
    }

    /**
     * 整页预加载分类名 / 品牌名 / SKU / 销量（各一次批量查询，替代逐条查库）
     *
     * @param products 当前页商品列表
     * @return 预加载上下文；空页返回各字段为空 Map 的实例
     */
    private ProductVoLookup loadLookup(List<ProductDO> products) {
        if (CollUtil.isEmpty(products)) {
            return new ProductVoLookup(Map.of(), Map.of(), Map.of(), Map.of());
        }

        Set<Long> categoryIds = products.stream().map(ProductDO::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> brandIds = products.stream().map(ProductDO::getBrandId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> productIds = products.stream().map(ProductDO::getId)
                .filter(Objects::nonNull).collect(Collectors.toList());

        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(CategoryDO::getId, CategoryDO::getName, (first, ignored) -> first));

        Map<Long, String> brandNames = brandIds.isEmpty() ? Map.of()
                : brandMapper.selectBatchIds(brandIds).stream()
                        .collect(Collectors.toMap(BrandDO::getId, BrandDO::getName, (first, ignored) -> first));

        // 批量查 SKU 后按商品分组：无 SKU 的商品不会出现在 Map 中（调用方 getOrDefault 取空列表）
        Map<Long, List<SkuDO>> skusByProduct = productIds.isEmpty() ? Map.of()
                : skuMapper.selectList(new LambdaQueryWrapper<SkuDO>().in(SkuDO::getProductId, productIds))
                        .stream().collect(Collectors.groupingBy(SkuDO::getProductId));

        // 批量查销量：SQL 侧 GROUP BY 只返回有销量的商品，无销量的按 0 处理
        Map<Long, Integer> soldCounts = productIds.isEmpty() ? Map.of()
                : orderItemMapper.sumQuantityByProductIds(productIds).stream()
                        .filter(dto -> dto.getProductId() != null)
                        .collect(Collectors.toMap(ProductSalesDTO::getProductId,
                                dto -> dto.getTotal() != null ? dto.getTotal() : 0, (first, ignored) -> first));

        return new ProductVoLookup(categoryNames, brandNames, skusByProduct, soldCounts);
    }
}
