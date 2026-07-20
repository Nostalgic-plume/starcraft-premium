package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.common.Result;
import com.shop.common.PageResult;
import com.shop.model.dto.UserLoginDTO;
import com.shop.model.dto.UserRegisterDTO;
import com.shop.model.entity.UserDO;
import com.shop.constant.PermissionConstants;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.service.UserService;
import com.shop.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户接口
 * 提供注册、登录、获取用户信息、退出登录等 API
 */
@Tag(name = "用户模块")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AdminService adminService;

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Void> register(@Valid @RequestBody UserRegisterDTO dto) {
        userService.register(dto);
        return Result.success("注册成功");
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result<Map<String, Object>> login(@Valid @RequestBody UserLoginDTO dto) {
        String token = userService.login(dto);
        return Result.success(Map.of("token", token));
    }

    @SaCheckLogin
    @GetMapping("/info")
    @Operation(summary = "获取当前用户信息")
    public Result<UserDO> info() {
        return Result.success(userService.getCurrentUser());
    }

    @SaCheckLogin
    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.success();
    }

    @SaCheckLogin
    @GetMapping("/role")
    @Operation(summary = "获取当前用户角色编码列表")
    public Result<List<String>> getRoles() {
        return Result.success(StpUtil.getRoleList());
    }

    @SaCheckPermission(PermissionConstants.USER_MANAGE)
    @GetMapping("/list")
    @Operation(summary = "用户列表（管理后台，分页）")
    public Result<PageResult<UserDO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.success(adminService.pageUsers(page, pageSize, keyword, status));
    }
}
