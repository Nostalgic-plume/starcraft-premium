package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.shop.common.Result;
import com.shop.model.vo.ProductVO;
import com.shop.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品收藏接口（用户端）
 *
 * @author shop
 */
@Tag(name = "商品收藏模块")
@RestController
@RequestMapping("/api/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @SaCheckLogin(type = "user")
    @GetMapping("/list")
    @Operation(summary = "收藏商品列表（按收藏时间倒序，跳过已下架商品）")
    public Result<List<ProductVO>> list() {
        return Result.success(favoriteService.list());
    }

    @SaCheckLogin(type = "user")
    @GetMapping("/check/{productId}")
    @Operation(summary = "是否已收藏该商品")
    public Result<Boolean> check(@PathVariable Long productId) {
        return Result.success(favoriteService.isFavorite(productId));
    }

    @SaCheckLogin(type = "user")
    @GetMapping("/count")
    @Operation(summary = "收藏总数")
    public Result<Long> count() {
        return Result.success(favoriteService.count());
    }

    @SaCheckLogin(type = "user")
    @PostMapping("/{productId}")
    @Operation(summary = "收藏商品（幂等）")
    public Result<Void> add(@PathVariable Long productId) {
        favoriteService.add(productId);
        return Result.success("已收藏", null);
    }

    @SaCheckLogin(type = "user")
    @DeleteMapping("/{productId}")
    @Operation(summary = "取消收藏")
    public Result<Void> remove(@PathVariable Long productId) {
        favoriteService.remove(productId);
        return Result.success("已取消收藏", null);
    }
}
