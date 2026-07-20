package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.PageResult;
import com.shop.common.Result;
import com.shop.constant.PermissionConstants;
import com.shop.model.dto.ProductDTO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import com.shop.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 商品接口
 * 浏览类接口无需登录；新增/编辑/上下架需要对应权限
 */
@Tag(name = "商品模块")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/page")
    @Operation(summary = "商品分页列表")
    public Result<PageResult<ProductVO>> page(@Valid ProductPageQuery query) {
        return Result.success(productService.page(query));
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "商品详情")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.success(productService.getDetail(id));
    }

    @SaCheckPermission(PermissionConstants.PRODUCT_ADD)
    @PostMapping("/add")
    @Operation(summary = "新增商品（商家/管理员）")
    public Result<Long> add(@Valid @RequestBody ProductDTO dto) {
        return Result.success("新增成功", productService.addProduct(dto));
    }

    @SaCheckPermission(PermissionConstants.PRODUCT_STATUS)
    @PutMapping("/status/{id}")
    @Operation(summary = "更新商品状态（上架/下架，商家/管理员）")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.updateStatus(id, status);
        return Result.success();
    }
}
