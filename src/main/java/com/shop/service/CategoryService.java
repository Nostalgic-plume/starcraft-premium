package com.shop.service;

import com.shop.model.entity.CategoryDO;

import java.util.List;

/**
 * 分类服务接口
 */
public interface CategoryService {

    /**
     * 获取所有启用的分类列表（按排序升序）
     */
    List<CategoryDO> list();

    /**
     * 获取指定父分类下的子分类列表
     *
     * @param parentId 父分类ID
     */
    List<CategoryDO> getChildren(Long parentId);
}
