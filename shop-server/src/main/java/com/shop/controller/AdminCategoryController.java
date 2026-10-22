package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.Result;
import com.shop.model.dto.CategoryDTO;
import com.shop.model.entity.CategoryDO;
import com.shop.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分类管理接口（管理后台）
 * <p>
 * 与公开的 {@link CategoryController} 分开：公开接口只读启用分类且走缓存，
 * 这里的列表含停用分类、增删改后立即清缓存。
 *
 * @author shop
 */
@Tag(name = "分类管理（管理后台）")
@RestController
@RequestMapping("/api/admin/category")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @GetMapping("/list")
    @Operation(summary = "分类列表（含停用分类）")
    public Result<List<CategoryDO>> list() {
        return Result.success(categoryService.adminList());
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @PostMapping
    @Operation(summary = "新增分类")
    public Result<Long> add(@Valid @RequestBody CategoryDTO dto) {
        return Result.success("新增成功", categoryService.add(dto));
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @PutMapping("/{id}")
    @Operation(summary = "修改分类")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategoryDTO dto) {
        categoryService.update(id, dto);
        return Result.success("修改成功", null);
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除分类（有子分类或商品引用时拒绝）")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success("删除成功", null);
    }
}
