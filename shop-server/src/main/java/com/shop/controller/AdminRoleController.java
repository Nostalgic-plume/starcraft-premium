package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.Result;
import com.shop.model.dto.RoleDTO;
import com.shop.model.entity.PermissionDO;
import com.shop.model.vo.RoleVO;
import com.shop.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色与权限管理接口（管理后台）
 * <p>
 * 权限本身由启动时的 RbacInitializer 幂等维护，这里只提供角色 CRUD、角色-权限分配与用户-角色分配。
 *
 * @author shop
 */
@Tag(name = "角色权限管理（管理后台）")
@RestController
@RequestMapping("/api/admin/role")
@RequiredArgsConstructor
public class AdminRoleController {

    private final RoleService roleService;

    @SaCheckPermission(value = {"user:manage"}, type = "admin")
    @GetMapping("/list")
    @Operation(summary = "角色列表（含已分配权限 ID）")
    public Result<List<RoleVO>> list() {
        return Result.success(roleService.list());
    }

    @SaCheckPermission(value = {"user:manage"}, type = "admin")
    @GetMapping("/permissions")
    @Operation(summary = "全部权限列表（角色编辑页勾选用）")
    public Result<List<PermissionDO>> permissions() {
        return Result.success(roleService.permissions());
    }

    @SaCheckPermission(value = {"user:manage"}, type = "admin")
    @PostMapping
    @Operation(summary = "新增角色")
    public Result<Long> add(@Valid @RequestBody RoleDTO dto) {
        return Result.success("新增成功", roleService.add(dto));
    }

    @SaCheckPermission(value = {"user:manage"}, type = "admin")
    @PutMapping("/{id}")
    @Operation(summary = "修改角色（编码不可改，权限全量覆盖）")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleDTO dto) {
        roleService.update(id, dto);
        return Result.success("修改成功", null);
    }

    @SaCheckPermission(value = {"user:manage"}, type = "admin")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除角色（内置角色 / 仍有用户关联时拒绝）")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.success("删除成功", null);
    }

    @SaCheckPermission(value = {"user:manage"}, type = "admin")
    @GetMapping("/user/{userId}")
    @Operation(summary = "查询用户已分配的角色 ID")
    public Result<List<Long>> userRoles(@PathVariable Long userId) {
        return Result.success(roleService.userRoleIds(userId));
    }

    @SaCheckPermission(value = {"user:manage"}, type = "admin")
    @PutMapping("/user/{userId}")
    @Operation(summary = "覆盖式分配用户角色")
    public Result<Void> assignUserRoles(@PathVariable Long userId, @RequestBody List<Long> roleIds) {
        roleService.assignUserRoles(userId, roleIds);
        return Result.success("角色分配成功", null);
    }
}
