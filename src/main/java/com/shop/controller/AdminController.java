package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.Result;
import com.shop.common.PageResult;
import com.shop.constant.PermissionConstants;
import com.shop.model.dto.AdminLoginDTO;
import com.shop.model.entity.AdminDO;
import com.shop.model.entity.UserDO;
import com.shop.model.vo.OrderVO;
import com.shop.service.AdminService;
import com.shop.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员后台接口
 * 管理员使用 admin 表独立账号登录，admin_id 即 Sa-Token loginId
 * 提供用户管理、订单管理、状态变更等后台管理 API
 */
@Slf4j
@Tag(name = "管理员模块")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final OrderService orderService;

    // ==================== 管理员独立登录 ====================

    @PostMapping("/login")
    @Operation(summary = "管理员登录（使用 admin 表独立账号）")
    public Result<Map<String, Object>> login(@Valid @RequestBody AdminLoginDTO dto) {
        return Result.success(adminService.login(dto));
    }

    @GetMapping("/info")
    @Operation(summary = "获取当前管理员信息")
    public Result<AdminDO> adminInfo() {
        return Result.success(adminService.getCurrentAdmin());
    }

    // ==================== 用户管理 ====================

    @SaCheckPermission(PermissionConstants.USER_MANAGE)
    @GetMapping("/users")
    @Operation(summary = "用户分页列表（支持关键词搜索和状态筛选）")
    public Result<PageResult<UserDO>> pageUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.success(adminService.pageUsers(page, pageSize, keyword, status));
    }

    @SaCheckPermission(PermissionConstants.USER_MANAGE)
    @PutMapping("/user/{id}/status")
    @Operation(summary = "修改用户状态")
    public Result<Void> updateUserStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Integer status = (Integer) body.get("status");
        String reason = (String) body.getOrDefault("reason", "");
        adminService.updateUserStatus(id, status, reason);
        return Result.success();
    }

    @SaCheckPermission(PermissionConstants.USER_MANAGE)
    @PutMapping("/user/batch-status")
    @Operation(summary = "批量修改用户状态")
    public Result<Void> batchUpdateUserStatus(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<?>) body.get("ids")).stream()
                .map(o -> Long.valueOf(o.toString())).toList();
        Integer status = (Integer) body.get("status");
        String reason = (String) body.getOrDefault("reason", "");
        adminService.batchUpdateUserStatus(ids, status, reason);
        return Result.success();
    }

    // ==================== 订单管理 ====================

    @SaCheckPermission(PermissionConstants.ORDER_ALL)
    @GetMapping("/order/page")
    @Operation(summary = "订单分页列表（多条件筛选，全平台数据）")
    public Result<PageResult<OrderVO>> orderPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String keyword) {
        return Result.success(orderService.adminPageOrders(page, pageSize, status, shopId, startTime, endTime, keyword));
    }

    @SaCheckPermission(PermissionConstants.ORDER_ALL)
    @GetMapping("/order/detail/{orderId}")
    @Operation(summary = "订单详情（管理员）")
    public Result<OrderVO> orderDetail(@PathVariable Long orderId) {
        return Result.success(orderService.adminGetDetail(orderId));
    }
}
