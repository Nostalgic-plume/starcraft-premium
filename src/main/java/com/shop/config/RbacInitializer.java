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
 * RBAC 权限数据初始化器（增量同步）
 * 应用启动时幂等同步「角色 + 权限 + 角色-权限关联」：
 * - 权限按 code 判断存在性，缺则插入——后续新增权限可平滑同步到已有角色（如 stats:view）
 * - 角色-权限关联缺则插入（admin 全量、merchant 商品管理）
 * - 角色不存在（全新库）时，才为 user 表已有账号分配初始角色，避免每次启动重置手工调整的角色
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
        log.info("========== RBAC 权限增量同步开始 ==========");

        // 全新库判断：admin 角色不存在视为首次初始化
        boolean freshInit = roleMapper.selectCount(
                new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getCode, "admin")) == 0;

        // ① 幂等创建角色（按 code 判断，已存在则复用）
        RoleDO adminRole = ensureRole(100L, "管理员", "admin", "系统管理员，拥有全部权限");
        RoleDO merchantRole = ensureRole(101L, "商家", "merchant", "商家，可管理商品");
        RoleDO userRole = ensureRole(102L, "普通用户", "user", "注册用户，可浏览/下单");

        // ② 幂等创建权限（按 code 判断，缺则插入；后续新增权限自动同步到已有角色）
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
        int permInserted = 0;
        for (PermDef p : allPerms) {
            Long cnt = permissionMapper.selectCount(
                    new LambdaQueryWrapper<PermissionDO>().eq(PermissionDO::getCode, p.code));
            if (cnt > 0) continue;
            PermissionDO perm = new PermissionDO();
            perm.setId(p.id); perm.setName(p.name); perm.setCode(p.code);
            perm.setParentId(0L); perm.setSort(0); perm.setStatus(0);
            permissionMapper.insert(perm);
            permInserted++;
        }

        // ③ 幂等分配 角色-权限关联（缺则插入）
        int rpInserted = 0;
        // admin → 全部权限
        for (PermDef p : allPerms) {
            Long cnt = rolePermissionMapper.selectCount(new LambdaQueryWrapper<RolePermissionDO>()
                    .eq(RolePermissionDO::getRoleId, adminRole.getId())
                    .eq(RolePermissionDO::getPermissionId, p.id));
            if (cnt > 0) continue;
            RolePermissionDO rp = new RolePermissionDO();
            rp.setRoleId(adminRole.getId()); rp.setPermissionId(p.id);
            rolePermissionMapper.insert(rp);
            rpInserted++;
        }
        // merchant → 商品管理（不含删除）
        List<String> merchantPerms = List.of(
                PermissionConstants.PRODUCT_ADD,
                PermissionConstants.PRODUCT_EDIT,
                PermissionConstants.PRODUCT_STATUS);
        for (PermDef p : allPerms) {
            if (!merchantPerms.contains(p.code)) continue;
            Long cnt = rolePermissionMapper.selectCount(new LambdaQueryWrapper<RolePermissionDO>()
                    .eq(RolePermissionDO::getRoleId, merchantRole.getId())
                    .eq(RolePermissionDO::getPermissionId, p.id));
            if (cnt > 0) continue;
            RolePermissionDO rp = new RolePermissionDO();
            rp.setRoleId(merchantRole.getId()); rp.setPermissionId(p.id);
            rolePermissionMapper.insert(rp);
            rpInserted++;
        }

        // ④ 仅首次初始化时为用户分配初始角色（避免每次启动重置手工调整的角色）
        if (freshInit) {
            List<UserDO> users = userMapper.selectList(null);
            for (UserDO u : users) {
                Long roleId = switch (u.getUsername()) {
                    case "admin" -> adminRole.getId();
                    case "merchant" -> merchantRole.getId();
                    default -> userRole.getId();
                };
                UserRoleDO ur = new UserRoleDO();
                ur.setUserId(u.getId()); ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
            log.info("已为 {} 个用户分配初始角色", users.size());
        }
        // 商家为独立账号体系（shop 表 username/password 直接登录，以 shop_id 为身份标识），无需经 user_role 建店

        log.info("RBAC 增量同步完成: 新增权限 {} 条, 新增角色-权限关联 {} 条{}",
                permInserted, rpInserted, freshInit ? ", 已为用户分配初始角色" : "");
        log.info("========== RBAC 增量同步结束 ==========");
    }

    /** 幂等创建角色（按 code 判断，已存在则复用） */
    private RoleDO ensureRole(Long id, String name, String code, String description) {
        RoleDO exist = roleMapper.selectOne(new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getCode, code));
        if (exist != null) return exist;
        RoleDO role = new RoleDO();
        role.setId(id); role.setName(name); role.setCode(code);
        role.setDescription(description); role.setStatus(0);
        roleMapper.insert(role);
        log.info("创建角色: {} ({})", name, code);
        return role;
    }
}
