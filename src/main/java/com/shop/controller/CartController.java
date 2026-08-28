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
 *
 * @author shop
 */
@Tag(name = "购物车模块")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /**
     * 查询当前登录用户的购物车列表
     * 自动过滤已下单（is_ordered=1）的商品项
     *
     * @return 购物车商品项列表
     */
    @SaCheckLogin
    @GetMapping("/list")
    @Operation(summary = "购物车列表")
    public Result<List<CartVO>> list() {
        return Result.success(cartService.list());
    }

    /**
     * 将指定 SKU 加入购物车
     * 重复添加时累加数量，商品参数由 @Valid 校验
     *
     * @param dto 添加请求体（含 skuId、数量等）
     * @return 操作成功提示
     */
    @SaCheckLogin
    @PostMapping("/add")
    @Operation(summary = "添加到购物车")
    public Result<Void> add(@Valid @RequestBody CartDTO dto) {
        cartService.addItem(dto);
        return Result.success("已添加到购物车", null);
    }

    /**
     * 修改购物车中指定 SKU 的购买数量
     *
     * @param skuId    SKU 商品ID
     * @param quantity 新的数量，最小为 1（@Min 校验，小于 1 拒绝）
     * @return 操作成功提示
     */
    @SaCheckLogin
    @PutMapping("/quantity/{skuId}")
    @Operation(summary = "修改数量")
    public Result<Void> updateQuantity(@PathVariable Long skuId, @RequestParam @Min(value = 1, message = "数量不能小于1") Integer quantity) {
        cartService.updateQuantity(skuId, quantity);
        return Result.success();
    }

    /**
     * 删除购物车中的指定 SKU 商品项
     *
     * @param skuId SKU 商品ID
     * @return 操作成功提示
     */
    @SaCheckLogin
    @DeleteMapping("/remove/{skuId}")
    @Operation(summary = "删除购物车商品")
    public Result<Void> remove(@PathVariable Long skuId) {
        cartService.removeItem(skuId);
        return Result.success();
    }

    /**
     * 选中/取消选中购物车中的指定 SKU 商品项
     *
     * @param skuId   SKU 商品ID
     * @param checked 是否选中：true=选中，false=取消选中
     * @return 操作成功提示
     */
    @SaCheckLogin
    @PutMapping("/check/{skuId}")
    @Operation(summary = "选中/取消选中")
    public Result<Void> check(@PathVariable Long skuId, @RequestParam Boolean checked) {
        cartService.checkItem(skuId, checked);
        return Result.success();
    }
}
