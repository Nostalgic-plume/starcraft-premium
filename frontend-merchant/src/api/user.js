import request from './request'

/** 商家端用户 API */
export const userApi = {
  /** 登录 */
  login(data) { return request.post('/user/login', data) },

  /** 获取当前用户信息 */
  getInfo() { return request.get('/user/info') },

  /** 退出登录 */
  logout() { return request.post('/user/logout') }
}
