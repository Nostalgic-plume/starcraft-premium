import request from './request'

/** 商家端商品 API */
export const productApi = {
  /** 商品分页 */
  page(params) { return request.get('/product/page', { params }) },

  /** 商品详情 */
  detail(id) { return request.get('/product/detail/' + id) },

  /** 新增商品 */
  add(data) { return request.post('/product/add', data) },

  /** 更新商品状态 */
  updateStatus(id, status) { return request.put('/product/status/' + id, null, { params: { status } }) }
}
