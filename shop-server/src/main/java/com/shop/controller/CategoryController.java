package com.shop.controller;

import com.shop.common.Result;
import com.shop.model.entity.CategoryDO;
import com.shop.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口
 * 提供商品分类列表查询，支持一级/二级分类联动
 *
 * @author shop
 */
@Tag(name = "分类模块")
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 获取分类列表
     * 按层级扁平返回，parentId 为 0 即一级分类，前端可自行组装树形结构
     *
     * @return 全部分类列表（含一级/二级分类）
     */
    @GetMapping("/list")
    @Operation(summary = "获取分类列表（按层级返回，前端可自行组装树形）")
    public Result<List<CategoryDO>> list() {
        return Result.success(categoryService.list());
    }

    /**
     * 获取指定父分类下的子分类列表
     * parentId 为一级分类ID时返回其下全部二级分类
     *
     * @param parentId 父分类ID
     * @return 子分类列表（无子分类时返回空列表）
     */
    @GetMapping("/children")
    @Operation(summary = "获取指定父分类下的子分类列表")
    public Result<List<CategoryDO>> children(@RequestParam Long parentId) {
        return Result.success(categoryService.getChildren(parentId));
    }
}
