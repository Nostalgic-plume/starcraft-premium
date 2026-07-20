import request from './request'

export const userApi = {
  register(data) { return request.post('/user/register', data) },
  login(data) { return request.post('/user/login', data) },
  getInfo() { return request.get('/user/info') },
  getRole() { return request.get('/user/role') },
  logout() { return request.post('/user/logout') }
}
