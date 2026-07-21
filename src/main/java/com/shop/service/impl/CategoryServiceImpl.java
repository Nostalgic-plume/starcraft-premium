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
import java.util.concurrent.TimeUnit;

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
        String cacheKey = ProductConstants.CACHE_CATEGORY + "all";
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JSONUtil.toList(cached, CategoryDO.class);
        }
        List<CategoryDO> list = categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getStatus, 0)
                        .orderByAsc(CategoryDO::getSort));
        redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(list), 1, TimeUnit.HOURS);
        return list;
    }

    @Override
    public List<CategoryDO> getChildren(Long parentId) {
        String cacheKey = ProductConstants.CACHE_CATEGORY + "children:" + parentId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JSONUtil.toList(cached, CategoryDO.class);
        }
        List<CategoryDO> list = categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getParentId, parentId)
                        .eq(CategoryDO::getStatus, 0)
                        .orderByAsc(CategoryDO::getSort));
        redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(list), 1, TimeUnit.HOURS);
        return list;
    }
}
