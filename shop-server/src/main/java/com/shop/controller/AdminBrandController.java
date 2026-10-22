package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.PageResult;
import com.shop.common.Result;
import com.shop.model.dto.BrandDTO;
import com.shop.model.entity.BrandDO;
import com.shop.service.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 品牌管理接口（管理后台）
 *
 * @author shop
 */
@Tag(name = "品牌管理（管理后台）")
@RestController
@RequestMapping("/api/admin/brand")
@RequiredArgsConstructor
public class AdminBrandController {

    private final BrandService brandService;

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @GetMapping("/page")
    @Operation(summary = "品牌分页（含停用品牌，支持名称搜索）")
    public Result<PageResult<BrandDO>> page(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword) {
        return Result.success(brandService.adminPage(page, pageSize, keyword));
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @PostMapping
    @Operation(summary = "新增品牌")
    public Result<Long> add(@Valid @RequestBody BrandDTO dto) {
        return Result.success("新增成功", brandService.add(dto));
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @PutMapping("/{id}")
    @Operation(summary = "修改品牌")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BrandDTO dto) {
        brandService.update(id, dto);
        return Result.success("修改成功", null);
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除品牌（有商品引用时拒绝）")
    public Result<Void> delete(@PathVariable Long id) {
        brandService.delete(id);
        return Result.success("删除成功", null);
    }
}
