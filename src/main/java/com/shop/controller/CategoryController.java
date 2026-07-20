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
 */
@Tag(name = "分类模块")
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/list")
    @Operation(summary = "获取分类列表（按层级返回，前端可自行组装树形）")
    public Result<List<CategoryDO>> list() {
        return Result.success(categoryService.list());
    }

    @GetMapping("/children")
    @Operation(summary = "获取指定父分类下的子分类列表")
    public Result<List<CategoryDO>> children(@RequestParam Long parentId) {
        return Result.success(categoryService.getChildren(parentId));
    }
}
