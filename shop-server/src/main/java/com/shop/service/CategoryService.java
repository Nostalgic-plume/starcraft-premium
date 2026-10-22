package com.shop.service;

import com.shop.model.dto.CategoryDTO;
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

    /**
     * 管理端分类列表（含停用分类，按 parentId / sort 升序）
     * <p>
     * 与 {@link #list()} 的区别：不过滤状态、不走缓存，供后台分类管理页使用。
     *
     * @return 全部分类
     */
    List<CategoryDO> adminList();

    /**
     * 新增分类
     *
     * @param dto 分类参数
     * @return 新分类 ID
     * @throws com.shop.common.BusinessException 父分类不存在 / 超过两级 / 名称非法
     */
    Long add(CategoryDTO dto);

    /**
     * 修改分类
     *
     * @param id  分类 ID
     * @param dto 分类参数
     * @throws com.shop.common.BusinessException 分类不存在 / 父分类非法 / 有子分类时降级为二级
     */
    void update(Long id, CategoryDTO dto);

    /**
     * 删除分类
     *
     * @param id 分类 ID
     * @throws com.shop.common.BusinessException 分类不存在 / 有子分类 / 有商品引用
     */
    void delete(Long id);
}
