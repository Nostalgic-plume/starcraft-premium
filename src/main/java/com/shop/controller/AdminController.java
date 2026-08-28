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
 *
 * @author shop
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

    /**
     * 管理员登录
     * 使用 admin 表独立账号登录，成功后返回 Sa-Token token
     *
     * @param dto 登录请求体（含用户名、密码），由 @Valid 做非空与格式校验
     * @return 登录结果，data 为 token 及账号信息的 Map
     */
    @PostMapping("/login")
    @Operation(summary = "管理员登录（使用 admin 表独立账号）")
    public Result<Map<String, Object>> login(@Valid @RequestBody AdminLoginDTO dto) {
        return Result.success(adminService.login(dto));
    }

    /**
     * 获取当前登录管理员信息
     *
     * @return 当前管理员账号信息（admin 表记录）
     */
    @GetMapping("/info")
    @Operation(summary = "获取当前管理员信息")
    public Result<AdminDO> adminInfo() {
        return Result.success(adminService.getCurrentAdmin());
    }

    // ==================== 用户管理 ====================

    /**
     * 用户分页列表，支持关键词搜索与状态筛选（需 user:manage 权限）
     *
     * @param page     页码，从 1 开始，默认 1
     * @param pageSize 每页条数，默认 20
     * @param keyword  搜索关键词（用户名/手机号等），可空表示不筛选
     * @param status   用户状态：0=正常 1=停用 2=注销中 3=封禁中，可空表示全部
     * @return 用户分页结果
     */
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

    /**
     * 修改单个用户状态，并记录状态变更日志
     *
     * @param id   用户ID
     * @param body 请求体：status 为目标状态（0=正常 1=停用 2=注销中 3=封禁中），reason 为变更原因（可空，默认空串）
     * @return 操作成功提示
     */
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

    /**
     * 批量修改用户状态，逐条记录状态变更日志
     *
     * @param body 请求体：ids 为用户ID列表（非空），status 为目标状态（0=正常 1=停用 2=注销中 3=封禁中），reason 为变更原因（可空）
     * @return 操作成功提示
     */
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

    /**
     * 全平台订单分页列表，支持多条件筛选（需 order:all 权限）
     *
     * @param page      页码，从 1 开始，默认 1
     * @param pageSize  每页条数，默认 20
     * @param status    订单状态：0=待支付 1=已支付 2=已发货 3=已收货 4=已完成 5=已取消 6=已退款，可空表示全部
     * @param shopId    商家ID筛选，可空表示全部商家
     * @param startTime 下单时间范围起（yyyy-MM-dd HH:mm:ss），可空
     * @param endTime   下单时间范围止（yyyy-MM-dd HH:mm:ss），可空
     * @param keyword   搜索关键词（订单号/用户名等），可空
     * @return 订单分页结果
     */
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

    /**
     * 查询全平台订单详情（管理员视角，不做商家隔离）
     *
     * @param orderId 订单ID
     * @return 订单详情
     */
    @SaCheckPermission(PermissionConstants.ORDER_ALL)
    @GetMapping("/order/detail/{orderId}")
    @Operation(summary = "订单详情（管理员）")
    public Result<OrderVO> orderDetail(@PathVariable Long orderId) {
        return Result.success(orderService.adminGetDetail(orderId));
    }
}
