package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.common.Result;
import com.shop.common.StpUserUtil;
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
 *
 * @author 陈
 */
@Tag(name = "用户模块")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AdminService adminService;

    /**
     * 用户注册（公开接口）
     *
     * @param dto 注册请求体（含用户名、密码、手机号等），由 @Valid 校验
     * @return 注册成功提示
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Void> register(@Valid @RequestBody UserRegisterDTO dto) {
        userService.register(dto);
        return Result.success("注册成功");
    }

    /**
     * 用户登录（公开接口）
     * 状态为停用/注销中/封禁的账号禁止登录
     *
     * @param dto 登录请求体（含用户名、密码），由 @Valid 校验
     * @return 登录结果，data 为 {token: xxx}
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result<Map<String, Object>> login(@Valid @RequestBody UserLoginDTO dto) {
        String token = userService.login(dto);
        return Result.success(Map.of("token", token));
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前用户账号信息
     */
    @SaCheckLogin(type ="user")
    @GetMapping("/info")
    @Operation(summary = "获取当前用户信息")
    public Result<UserDO> info() {
        return Result.success(userService.getCurrentUser());
    }

    /**
     * 退出登录，注销当前 Sa-Token 会话
     *
     * @return 操作成功提示
     */
    @SaCheckLogin(type ="user")
    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public Result<Void> logout() {
        StpUserUtil.INSTANCE.logout();
        return Result.success();
    }

    /**
     * 获取当前登录用户的角色编码列表
     *
     * @return 角色编码列表（如 user/merchant/admin），未分配角色时为空列表
     */
    @SaCheckLogin(type ="user")
    @GetMapping("/role")
    @Operation(summary = "获取当前用户角色编码列表")
    public Result<List<String>> getRoles() {
        return Result.success(StpUserUtil.INSTANCE.getRoleList());
    }

    /**
     * 用户分页列表（管理后台，需 user:manage 权限）
     *
     * @param page     页码，从 1 开始，默认 1
     * @param pageSize 每页条数，默认 20
     * @param keyword  搜索关键词（用户名/手机号等），可空
     * @param status   用户状态：0=正常 1=停用 2=注销中 3=封禁中，可空表示全部
     * @return 用户分页结果
     */
    @SaCheckPermission(value = {PermissionConstants.USER_MANAGE}, type ="admin")
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
