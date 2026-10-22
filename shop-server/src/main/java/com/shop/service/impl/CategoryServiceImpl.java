package com.shop.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.dto.CategoryDTO;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ProductDO;
import com.shop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分类服务实现
 * <p>
 * 读接口走 Redis 缓存（key 前缀 {@code category:list}）；**写操作必须清缓存**，
 * 否则分类改名 / 新增后在 TTL（约 1 小时）内前台仍看到旧数据。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final int STATUS_ENABLED = 0;
    private static final long ROOT_PARENT_ID = 0L;

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 查询启用状态的一级分类列表（按 sort 升序），走缓存穿透防护（查缓存→回源 DB→写缓存）
     *
     * @return 启用分类列表 {@code List<CategoryDO>}
     */
    @Override
    public List<CategoryDO> list() {
        return cacheThrough("all", () -> categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getStatus, STATUS_ENABLED)
                        .orderByAsc(CategoryDO::getSort)));
    }

    /**
     * 按父级分类ID查询其下的启用子分类列表（按 sort 升序），走缓存穿透防护
     *
     * @param parentId 父分类ID（查询二级分类时传一级分类ID）
     * @return 子分类列表 {@code List<CategoryDO>}
     */
    @Override
    public List<CategoryDO> getChildren(Long parentId) {
        return cacheThrough("children:" + parentId, () -> categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getParentId, parentId)
                        .eq(CategoryDO::getStatus, STATUS_ENABLED)
                        .orderByAsc(CategoryDO::getSort)));
    }

    @Override
    public List<CategoryDO> adminList() {
        // 管理端要看到停用分类，直接回源，不走缓存：后台查询频率低，且写后立即可见更重要
        return categoryMapper.selectList(new LambdaQueryWrapper<CategoryDO>()
                .orderByAsc(CategoryDO::getParentId)
                .orderByAsc(CategoryDO::getSort));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(CategoryDTO dto) {
        long parentId = normalizeParentId(dto.getParentId());
        validateParent(parentId, null);

        CategoryDO category = new CategoryDO();
        category.setName(dto.getName().trim());
        category.setParentId(parentId);
        category.setSort(dto.getSort() == null ? 0 : dto.getSort());
        category.setIcon(dto.getIcon());
        category.setStatus(dto.getStatus() == null ? STATUS_ENABLED : dto.getStatus());
        categoryMapper.insert(category);

        clearCache();
        log.info("分类新增成功, categoryId: {}, parentId: {}", category.getId(), parentId);
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategoryDTO dto) {
        CategoryDO existing = requireExists(id);
        long parentId = normalizeParentId(dto.getParentId());
        validateParent(parentId, id);

        // 一级分类改挂到二级分类下会破坏两级结构：有子分类时不允许变更为二级
        if (existing.getParentId() != null && existing.getParentId() == ROOT_PARENT_ID && parentId != ROOT_PARENT_ID) {
            long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<CategoryDO>()
                    .eq(CategoryDO::getParentId, id));
            if (childCount > 0) {
                throw new BusinessException("该分类下还有子分类，不能改为二级分类");
            }
        }

        existing.setName(dto.getName().trim());
        existing.setParentId(parentId);
        existing.setSort(dto.getSort() == null ? existing.getSort() : dto.getSort());
        existing.setIcon(dto.getIcon());
        if (dto.getStatus() != null) {
            existing.setStatus(dto.getStatus());
        }
        categoryMapper.updateById(existing);

        clearCache();
        log.info("分类修改成功, categoryId: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireExists(id);

        long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<CategoryDO>()
                .eq(CategoryDO::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException("该分类下还有子分类，请先删除子分类");
        }
        long productCount = productMapper.selectCount(new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getCategoryId, id));
        if (productCount > 0) {
            throw new BusinessException("该分类下还有 " + productCount + " 个商品，不能删除");
        }

        categoryMapper.deleteById(id);
        clearCache();
        log.info("分类删除成功, categoryId: {}", id);
    }

    /** 缓存穿透防护：查缓存 → 回源 DB → 写缓存 */
    private List<CategoryDO> cacheThrough(String suffix, Supplier<List<CategoryDO>> query) {
        String cacheKey = ProductConstants.CACHE_CATEGORY + suffix;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JSONUtil.toList(cached, CategoryDO.class);
        }
        List<CategoryDO> list = query.get();
        // TTL 随机化（基准 1h + 随机 0~120s）：分散各分类缓存的过期时间，避免同时过期引发缓存雪崩
        long ttlSeconds = 3600 + ThreadLocalRandom.current().nextInt(120);
        redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(list), ttlSeconds, TimeUnit.SECONDS);
        return list;
    }

    /**
     * 清空全部分类缓存
     * <p>
     * 分类总量仅几十条，{@code keys} 扫描成本可忽略；写操作频率极低（后台手工维护）。
     * 若将来分类数量或写频率上升，应改为按 parentId 精确删除 + 版本号方案。
     */
    private void clearCache() {
        Set<String> keys = redisTemplate.keys(ProductConstants.CACHE_CATEGORY + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private CategoryDO requireExists(Long id) {
        CategoryDO category = id == null ? null : categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        return category;
    }

    private static long normalizeParentId(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    /**
     * 校验父分类合法性
     * <p>
     * 只允许两级：父必须是一级分类（parentId=0），且不能是自己。
     *
     * @param parentId  目标父分类 ID（0 表示一级分类）
     * @param selfId    当前分类 ID（新增时为 null）
     */
    private void validateParent(long parentId, Long selfId) {
        if (parentId == ROOT_PARENT_ID) {
            return;
        }
        if (selfId != null && parentId == selfId) {
            throw new BusinessException("父分类不能是自己");
        }
        CategoryDO parent = categoryMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException("父分类不存在");
        }
        if (parent.getParentId() != null && parent.getParentId() != ROOT_PARENT_ID) {
            throw new BusinessException("分类最多两级，父分类必须是一级分类");
        }
    }
}
