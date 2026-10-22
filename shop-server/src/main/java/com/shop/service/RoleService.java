package com.shop.service;

import com.shop.model.dto.RoleDTO;
import com.shop.model.entity.PermissionDO;
import com.shop.model.vo.RoleVO;

import java.util.List;

/**
 * 角色与权限管理服务
 * <p>
 * 覆盖后台最基础的 RBAC 维护：角色 CRUD、角色-权限分配、用户-角色分配。
 * 权限本身（permission 表）由 {@code RbacInitializer} 在启动时幂等同步，不开放增删改。
 *
 * @author shop
 */
public interface RoleService {

    /**
     * 角色列表（含已分配权限 ID）
     *
     * @return 角色视图列表
     */
    List<RoleVO> list();

    /**
     * 新增角色
     *
     * @param dto 角色参数
     * @return 新角色 ID
     * @throws com.shop.common.BusinessException 编码重复
     */
    Long add(RoleDTO dto);

    /**
     * 修改角色（编码不可改；权限列表全量覆盖）
     *
     * @param id  角色 ID
     * @param dto 角色参数
     * @throws com.shop.common.BusinessException 角色不存在
     */
    void update(Long id, RoleDTO dto);

    /**
     * 删除角色
     *
     * @param id 角色 ID
     * @throws com.shop.common.BusinessException 内置角色不可删 / 仍有用户关联
     */
    void delete(Long id);

    /**
     * 全部权限列表（供角色编辑页勾选）
     *
     * @return 权限列表
     */
    List<PermissionDO> permissions();

    /**
     * 查询某用户已分配的角色 ID
     *
     * @param userId 用户 ID
     * @return 角色 ID 列表
     */
    List<Long> userRoleIds(Long userId);

    /**
     * 覆盖式分配用户角色
     *
     * @param userId  用户 ID
     * @param roleIds 角色 ID 列表（空列表表示清空）
     * @throws com.shop.common.BusinessException 用户或角色不存在
     */
    void assignUserRoles(Long userId, List<Long> roleIds);
}
