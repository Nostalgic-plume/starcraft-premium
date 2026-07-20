import request from './request'

export const cartApi = {
  list() { return request.get('/cart/list') },
  add(data) { return request.post('/cart/add', data) },
  updateQuantity(skuId, quantity) { return request.put('/cart/quantity/' + skuId, null, { params: { quantity } }) },
  remove(skuId) { return request.delete('/cart/remove/' + skuId) },
  check(skuId, checked) { return request.put('/cart/check/' + skuId, null, { params: { checked } }) }
}
