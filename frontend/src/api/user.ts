import { get, post } from './request'
import type { LoginResult, Result, RoleList, UserVO } from './types'

/** 注册入参 */
export interface UserRegisterPayload {
  username: string
  password: string
  phone?: string
  email?: string
}

/** 登录入参 */
export interface UserLoginPayload {
  username: string
  password: string
}

export const userApi = {
  register(data: UserRegisterPayload) {
    return post<void>('/user/register', data)
  },
  login(data: UserLoginPayload): Promise<Result<LoginResult>> {
    return post<LoginResult>('/user/login', data)
  },
  getInfo(): Promise<Result<UserVO>> {
    return get<UserVO>('/user/info')
  },
  getRole(): Promise<Result<RoleList>> {
    return get<RoleList>('/user/role')
  },
  logout(): Promise<Result<void>> {
    return post<void>('/user/logout')
  }
}
