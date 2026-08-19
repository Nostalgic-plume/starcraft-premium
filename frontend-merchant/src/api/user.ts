import { get, post } from '@/api/request'
import type { LoginPayload, LoginResult, Result, ShopInfoVO } from './types'

/**
 * 商家端用户 API
 *
 * ⚠️ 本批仅做 TS 化，保留原 URL（用户端 /user/* 路径），
 * 当前未被任何视图引用（现有登录页直接调 request.post('/merchant/login')），
 * 批次2+ 页面重写时可评估清理或对齐商家端 /merchant/login 路径。
 */
export const userApi = {
  /** 登录 */
  login(data: LoginPayload): Promise<Result<LoginResult>> {
    return post<LoginResult>('/user/login', data)
  },

  /** 获取当前用户信息 */
  getInfo(): Promise<Result<ShopInfoVO>> {
    return get<ShopInfoVO>('/user/info')
  },

  /** 退出登录 */
  logout(): Promise<Result<null>> {
    return post<null>('/user/logout')
  }
}