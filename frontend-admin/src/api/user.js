import request from './request'

export const userApi = {
  login(data) { return request.post('/user/login', data) },
  getInfo() { return request.get('/user/info') },
  logout() { return request.post('/user/logout') }
}
