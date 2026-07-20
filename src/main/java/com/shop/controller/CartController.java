package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.shop.common.Result;
import com.shop.model.dto.CartDTO;
import com.shop.model.vo.CartVO;
import com.shop.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 购物车接口
 * 购物车增删改查及选中操作，颗粒度为 SKU 级别
 */
@Tag(name = "购物车模块")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @SaCheckLogin
    @GetMapping("/list")
    @Operation(summary = "购物车列表")
    public Result<List<CartVO>> list() {
        return Result.success(cartService.list());
    }

    @SaCheckLogin
    @PostMapping("/add")
    @Operation(summary = "添加到购物车")
    public Result<Void> add(@Valid @RequestBody CartDTO dto) {
        cartService.addItem(dto);
        return Result.success("已添加到购物车", null);
    }

    @SaCheckLogin
    @PutMapping("/quantity/{skuId}")
    @Operation(summary = "修改数量")
    public Result<Void> updateQuantity(@PathVariable Long skuId, @RequestParam @Min(value = 1, message = "数量不能小于1") Integer quantity) {
        cartService.updateQuantity(skuId, quantity);
        return Result.success();
    }

    @SaCheckLogin
    @DeleteMapping("/remove/{skuId}")
    @Operation(summary = "删除购物车商品")
    public Result<Void> remove(@PathVariable Long skuId) {
        cartService.removeItem(skuId);
        return Result.success();
    }

    @SaCheckLogin
    @PutMapping("/check/{skuId}")
    @Operation(summary = "选中/取消选中")
    public Result<Void> check(@PathVariable Long skuId, @RequestParam Boolean checked) {
        cartService.checkItem(skuId, checked);
        return Result.success();
    }
}
