import { get, put } from './request'
import type { PageResult, Result, UserPageQuery, UserStatusPayload, UserVO } from './types'

export const adminApi = {
  /** 用户分页列表（关键词 / 状态筛选） */
  pageUsers(params?: UserPageQuery): Promise<Result<PageResult<UserVO>>> {
    return get<PageResult<UserVO>>('/admin/users', { params })
  },
  /** 修改单个用户状态 */
  updateUserStatus(id: string | number, data: UserStatusPayload): Promise<Result<void>> {
    return put<void>('/admin/user/' + id + '/status', data)
  },
  /** 批量修改用户状态 */
  batchUpdateUserStatus(data: UserStatusPayload & { ids: string[] }): Promise<Result<void>> {
    return put<void>('/admin/user/batch-status', data)
  }
}