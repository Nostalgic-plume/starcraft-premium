import request from './request'

export const adminApi = {
  pageUsers(params) {
    return request.get('/admin/users', { params })
  },
  updateUserStatus(id, data) {
    return request.put('/admin/user/' + id + '/status', data)
  },
  batchUpdateUserStatus(data) {
    return request.put('/admin/user/batch-status', data)
  }
}
