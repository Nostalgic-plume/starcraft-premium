package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.PageResult;
import com.shop.common.Result;
import com.shop.model.vo.ReviewVO;
import com.shop.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 评价管理接口（管理后台）
 * <p>
 * 提供评价分页、显示/隐藏、删除与商家回复。权限复用 product:edit（评价属于商品内容运营）。
 *
 * @author shop
 */
@Tag(name = "评价管理（管理后台）")
@RestController
@RequestMapping("/api/admin/review")
@RequiredArgsConstructor
public class AdminReviewController {

    private final ReviewService reviewService;

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @GetMapping("/page")
    @Operation(summary = "评价分页（含隐藏评价，支持状态与商品名筛选）")
    public Result<PageResult<ReviewVO>> page(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(reviewService.adminPage(page, pageSize, status, keyword));
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @PutMapping("/{id}/status")
    @Operation(summary = "修改评价状态（0隐藏 1显示）")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        reviewService.updateStatus(id, status);
        return Result.success("状态已更新", null);
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除评价（物理删除，释放订单项）")
    public Result<Void> delete(@PathVariable Long id) {
        reviewService.delete(id);
        return Result.success("删除成功", null);
    }

    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @PutMapping("/{id}/reply")
    @Operation(summary = "商家回复评价")
    public Result<Void> reply(@PathVariable Long id, @RequestParam String reply) {
        reviewService.reply(id, reply);
        return Result.success("回复成功", null);
    }
}
