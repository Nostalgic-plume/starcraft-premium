/**
 * 角色与权限 API（管理后台）
 *
 * 后端：`/api/admin/role/*`
 * 权限项本身由后端启动时的 RbacInitializer 幂等维护，这里只读。
 */
import { get, post, put, del } from './request'
import type { Result } from './types'

/** 角色（含已分配权限 ID） */
export interface RoleVO {
  id: string
  name: string
  code: string
  description?: string
  status: number
  permissionIds: string[]
}

/** 权限项 */
export interface PermissionVO {
  id: string
  name: string
  code: string
  parentId: string
  sort: number
  status: number
}

export interface RolePayload {
  name: string
  code: string
  description?: string
  status?: number
  permissionIds?: string[]
}

export const roleApi = {
  /** 角色列表（含已分配权限 ID） */
  list(): Promise<Result<RoleVO[]>> {
    return get<RoleVO[]>('/admin/role/list')
  },
  /** 全部权限项（角色编辑页勾选） */
  permissions(): Promise<Result<PermissionVO[]>> {
    return get<PermissionVO[]>('/admin/role/permissions')
  },
  add(data: RolePayload): Promise<Result<number>> {
    return post<number>('/admin/role', data)
  },
  update(id: string, data: RolePayload): Promise<Result<void>> {
    return put<void>('/admin/role/' + id, data)
  },
  remove(id: string): Promise<Result<void>> {
    return del<void>('/admin/role/' + id)
  },
  /** 查询用户已分配的角色 ID */
  userRoles(userId: string): Promise<Result<string[]>> {
    return get<string[]>('/admin/role/user/' + userId)
  },
  /** 覆盖式分配用户角色 */
  assignUserRoles(userId: string, roleIds: string[]): Promise<Result<void>> {
    return put<void>('/admin/role/user/' + userId, roleIds)
  }
}
