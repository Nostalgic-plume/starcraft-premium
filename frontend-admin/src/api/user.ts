import { get, post } from './request'
import type { LoginPayload, LoginResult, Result, UserVO } from './types'

export const userApi = {
  login(data: LoginPayload): Promise<Result<LoginResult>> {
    return post<LoginResult>('/user/login', data)
  },
  getInfo(): Promise<Result<UserVO>> {
    return get<UserVO>('/user/info')
  },
  logout(): Promise<Result<void>> {
    return post<void>('/user/logout')
  }
}