import request from './request'

export const categoryApi = {
  list() { return request.get('/category/list') }
}
