package com.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.mapper.PermissionMapper;
import com.shop.mapper.RoleMapper;
import com.shop.mapper.RolePermissionMapper;
import com.shop.mapper.UserMapper;
import com.shop.mapper.UserRoleMapper;
import com.shop.model.dto.RoleDTO;
import com.shop.model.entity.PermissionDO;
import com.shop.model.entity.RoleDO;
import com.shop.model.entity.RolePermissionDO;
import com.shop.model.entity.UserDO;
import com.shop.model.entity.UserRoleDO;
import com.shop.model.vo.RoleVO;
import com.shop.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色与权限管理实现
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li><b>内置角色不可删</b>：admin / merchant / user 由 {@code RbacInitializer} 固定初始化，
 *       删掉会让对应登录端（三端独立 loginType）整体失去权限映射；</li>
 *   <li><b>角色编码不可改</b>：代码与初始化器都按 code 引用角色，改 code 会静默断链；</li>
 *   <li><b>关联表是物理删除</b>：role_permission / user_role 没有 deleted 字段，
 *       覆盖式分配用「先删后插」，必须放在事务内，避免中途失败留下半套权限。</li>
 * </ol>
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    /** 内置角色编码：不可删除 */
    private static final Set<String> BUILTIN_ROLES = Set.of("admin", "merchant", "user");
    private static final int STATUS_ENABLED = 0;

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;

    @Override
    public List<RoleVO> list() {
        List<RoleDO> roles = roleMapper.selectList(new LambdaQueryWrapper<RoleDO>()
                .orderByAsc(RoleDO::getId));
        if (roles.isEmpty()) {
            return List.of();
        }

        List<Long> roleIds = roles.stream().map(RoleDO::getId).toList();
        List<RolePermissionDO> links = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermissionDO>().in(RolePermissionDO::getRoleId, roleIds));
        Map<Long, List<Long>> permissionMap = links.stream().collect(Collectors.groupingBy(
                RolePermissionDO::getRoleId,
                Collectors.mapping(RolePermissionDO::getPermissionId, Collectors.toList())));

        return roles.stream().map(role -> {
            RoleVO vo = new RoleVO();
            vo.setId(role.getId());
            vo.setName(role.getName());
            vo.setCode(role.getCode());
            vo.setDescription(role.getDescription());
            vo.setStatus(role.getStatus());
            vo.setPermissionIds(permissionMap.getOrDefault(role.getId(), List.of()));
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(RoleDTO dto) {
        String code = dto.getCode().trim();
        Long exists = roleMapper.selectCount(new LambdaQueryWrapper<RoleDO>()
                .eq(RoleDO::getCode, code));
        if (exists > 0) {
            throw new BusinessException("角色编码已存在");
        }

        RoleDO role = new RoleDO();
        role.setName(dto.getName().trim());
        role.setCode(code);
        role.setDescription(dto.getDescription());
        role.setStatus(dto.getStatus() == null ? STATUS_ENABLED : dto.getStatus());
        roleMapper.insert(role);

        replacePermissions(role.getId(), dto.getPermissionIds());
        log.info("角色新增成功, roleId: {}, code: {}", role.getId(), code);
        return role.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, RoleDTO dto) {
        RoleDO role = requireExists(id);

        role.setName(dto.getName().trim());
        role.setDescription(dto.getDescription());
        if (dto.getStatus() != null) {
            role.setStatus(dto.getStatus());
        }
        // code 不接受修改：代码与初始化器都按 code 引用角色
        roleMapper.updateById(role);

        replacePermissions(id, dto.getPermissionIds());
        log.info("角色修改成功, roleId: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        RoleDO role = requireExists(id);
        if (BUILTIN_ROLES.contains(role.getCode())) {
            throw new BusinessException("内置角色不允许删除");
        }

        long userCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRoleDO>()
                .eq(UserRoleDO::getRoleId, id));
        if (userCount > 0) {
            throw new BusinessException("该角色下还有 " + userCount + " 个用户，请先解除关联");
        }

        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermissionDO>()
                .eq(RolePermissionDO::getRoleId, id));
        roleMapper.deleteById(id);
        log.info("角色删除成功, roleId: {}, code: {}", id, role.getCode());
    }

    @Override
    public List<PermissionDO> permissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<PermissionDO>()
                .orderByAsc(PermissionDO::getParentId)
                .orderByAsc(PermissionDO::getSort)
                .orderByAsc(PermissionDO::getId));
    }

    @Override
    public List<Long> userRoleIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleDO>()
                        .eq(UserRoleDO::getUserId, userId))
                .stream()
                .map(UserRoleDO::getRoleId)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignUserRoles(Long userId, List<Long> roleIds) {
        UserDO user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        List<Long> distinctRoleIds = roleIds == null ? List.of()
                : roleIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!distinctRoleIds.isEmpty()) {
            long found = roleMapper.selectCount(new LambdaQueryWrapper<RoleDO>()
                    .in(RoleDO::getId, distinctRoleIds));
            if (found != distinctRoleIds.size()) {
                throw new BusinessException("存在无效的角色 ID");
            }
        }

        // 覆盖式分配：先清空再插入，避免同一用户重复绑定同一角色
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleDO>()
                .eq(UserRoleDO::getUserId, userId));

        List<UserRoleDO> links = new ArrayList<>(distinctRoleIds.size());
        for (Long roleId : distinctRoleIds) {
            UserRoleDO link = new UserRoleDO();
            link.setUserId(userId);
            link.setRoleId(roleId);
            links.add(link);
        }
        for (UserRoleDO link : links) {
            userRoleMapper.insert(link);
        }
        log.info("用户角色已更新, userId: {}, roleIds: {}", userId, distinctRoleIds);
    }

    /**
     * 全量覆盖角色权限
     *
     * @param roleId        角色 ID
     * @param permissionIds 权限 ID 列表（null 视为清空）
     */
    private void replacePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermissionDO>()
                .eq(RolePermissionDO::getRoleId, roleId));

        List<Long> distinctIds = permissionIds == null ? List.of()
                : permissionIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return;
        }

        long found = permissionMapper.selectCount(new LambdaQueryWrapper<PermissionDO>()
                .in(PermissionDO::getId, distinctIds));
        if (found != distinctIds.size()) {
            throw new BusinessException("存在无效的权限 ID");
        }

        for (Long permissionId : distinctIds) {
            RolePermissionDO link = new RolePermissionDO();
            link.setRoleId(roleId);
            link.setPermissionId(permissionId);
            rolePermissionMapper.insert(link);
        }
    }

    private RoleDO requireExists(Long id) {
        RoleDO role = id == null ? null : roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        return role;
    }
}
