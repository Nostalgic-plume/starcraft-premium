package com.shop.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CategoryMapper;
import com.shop.model.entity.CategoryDO;
import com.shop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分类服务实现
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public List<CategoryDO> list() {
        return cacheThrough("all", () -> categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getStatus, 0)
                        .orderByAsc(CategoryDO::getSort)));
    }

    @Override
    public List<CategoryDO> getChildren(Long parentId) {
        return cacheThrough("children:" + parentId, () -> categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getParentId, parentId)
                        .eq(CategoryDO::getStatus, 0)
                        .orderByAsc(CategoryDO::getSort)));
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
}
