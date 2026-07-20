package com.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.mapper.CategoryMapper;
import com.shop.model.entity.CategoryDO;
import com.shop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类服务实现
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    @Override
    public List<CategoryDO> list() {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getStatus, 0)
                        .orderByAsc(CategoryDO::getSort));
    }

    @Override
    public List<CategoryDO> getChildren(Long parentId) {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<CategoryDO>()
                        .eq(CategoryDO::getParentId, parentId)
                        .eq(CategoryDO::getStatus, 0)
                        .orderByAsc(CategoryDO::getSort));
    }
}
