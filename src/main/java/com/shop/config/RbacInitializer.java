package com.shop.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.PermissionConstants;
import com.shop.mapper.*;
import com.shop.model.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RBAC 权限数据初始化器
 * 应用启动时检查并创建角色 + 权限 + 关联数据（幂等，仅首次运行生效）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RbacInitializer implements CommandLineRunner {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final ShopMapper shopMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(String... args) {
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getCode, "admin"));
        if (count > 0) { log.info("RBAC 数据已存在，跳过初始化"); return; }

        log.info("========== 初始化 RBAC 数据 ==========");

        // ① 创建角色
        RoleDO adminRole = new RoleDO(); adminRole.setId(100L); adminRole.setName("管理员"); adminRole.setCode("admin");
        adminRole.setDescription("系统管理员，拥有全部权限"); adminRole.setStatus(0); roleMapper.insert(adminRole);

        RoleDO merchantRole = new RoleDO(); merchantRole.setId(101L); merchantRole.setName("商家"); merchantRole.setCode("merchant");
        merchantRole.setDescription("商家，可管理商品"); merchantRole.setStatus(0); roleMapper.insert(merchantRole);

        RoleDO userRole = new RoleDO(); userRole.setId(102L); userRole.setName("普通用户"); userRole.setCode("user");
        userRole.setDescription("注册用户，可浏览/下单"); userRole.setStatus(0); roleMapper.insert(userRole);

        // ② 创建权限
        record PermDef(Long id, String name, String code) {}
        List<PermDef> allPerms = List.of(
                new PermDef(200L, "新增商品", PermissionConstants.PRODUCT_ADD),
                new PermDef(201L, "编辑商品", PermissionConstants.PRODUCT_EDIT),
                new PermDef(202L, "删除商品", PermissionConstants.PRODUCT_DELETE),
                new PermDef(203L, "上架/下架", PermissionConstants.PRODUCT_STATUS),
                new PermDef(204L, "查看所有订单", PermissionConstants.ORDER_ALL),
                new PermDef(205L, "用户管理", PermissionConstants.USER_MANAGE),
                new PermDef(206L, "查看统计", PermissionConstants.STATS_VIEW)
        );
        for (PermDef p : allPerms) {
            PermissionDO perm = new PermissionDO(); perm.setId(p.id); perm.setName(p.name);
            perm.setCode(p.code); perm.setParentId(0L); perm.setSort(0); perm.setStatus(0);
            permissionMapper.insert(perm);
        }

        // ③ 角色-权限分配
        // admin → 所有权限
        for (PermDef p : allPerms) {
            RolePermissionDO rp = new RolePermissionDO(); rp.setRoleId(adminRole.getId()); rp.setPermissionId(p.id);
            rolePermissionMapper.insert(rp);
        }
        // merchant → 商品管理（不含删除）
        for (PermDef p : allPerms) {
            if (List.of(PermissionConstants.PRODUCT_ADD, PermissionConstants.PRODUCT_EDIT, PermissionConstants.PRODUCT_STATUS).contains(p.code)) {
                RolePermissionDO rp = new RolePermissionDO(); rp.setRoleId(merchantRole.getId()); rp.setPermissionId(p.id);
                rolePermissionMapper.insert(rp);
            }
        }
        log.info("角色 admin 拥有 {} 个权限, merchant 拥有 3 个权限, user 无管理权限", allPerms.size());

        // ④ 为已有用户分配角色
        List<UserDO> users = userMapper.selectList(null);
        for (UserDO u : users) {
            Long roleId = switch (u.getUsername()) {
                case "admin" -> adminRole.getId();
                case "merchant" -> merchantRole.getId();
                default -> userRole.getId();
            };
            UserRoleDO ur = new UserRoleDO(); ur.setUserId(u.getId()); ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
        log.info("已为 {} 个用户分配角色", users.size());
        // 商家为独立账号体系（shop 表 username/password 直接登录，以 shop_id 为身份标识），无需经 user_role 建店
        log.info("========== RBAC 初始化完成 ==========");
}
}
