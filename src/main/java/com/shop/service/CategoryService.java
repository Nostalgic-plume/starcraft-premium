package com.shop.service;

import com.shop.model.entity.CategoryDO;

import java.util.List;

/**
 * 分类服务接口
 *
 * @author shop
 */
public interface CategoryService {

    /**
     * 获取所有启用的分类列表（按排序升序）
     *
     * @return 启用状态的分类列表，按排序值升序排列
     */
    List<CategoryDO> list();

    /**
     * 获取指定父分类下的子分类列表
     *
     * @param parentId 父分类ID（0 或空表示根分类）
     * @return 该父分类下的子分类列表
     */
    List<CategoryDO> getChildren(Long parentId);
}
