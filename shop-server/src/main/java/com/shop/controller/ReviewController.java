package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.shop.common.PageResult;
import com.shop.common.Result;
import com.shop.model.dto.ReviewCreateDTO;
import com.shop.model.vo.ReviewVO;
import com.shop.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品评价接口（用户端）
 * <p>
 * 发表 / 我的评价需要登录；商品评价列表为公开接口（仅返回显示中的评价）。
 *
 * @author shop
 */
@Tag(name = "商品评价模块")
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @SaCheckLogin(type = "user")
    @PostMapping
    @Operation(summary = "发表评价（订单项维度，一个订单项只能评价一次）")
    public Result<Void> create(@Valid @RequestBody ReviewCreateDTO dto) {
        reviewService.create(dto);
        return Result.success("评价成功", null);
    }

    @SaCheckLogin(type = "user")
    @GetMapping("/my")
    @Operation(summary = "我的评价列表")
    public Result<List<ReviewVO>> my() {
        return Result.success(reviewService.myReviews());
    }

    @GetMapping("/product/{productId}")
    @Operation(summary = "商品评价列表（公开，仅显示中的评价）")
    public Result<PageResult<ReviewVO>> product(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(reviewService.productReviews(productId, page, pageSize));
    }
}
