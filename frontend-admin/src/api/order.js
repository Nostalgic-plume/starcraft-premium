import request from './request'

export const orderApi = {
  myOrders(params) { return request.get('/order/my', { params }) },
  detail(id) { return request.get('/order/detail/' + id) },
  cancel(id) { return request.post('/order/cancel/' + id) },
  receive(id) { return request.post('/order/receive/' + id) },
  deliver(id) { return request.post('/order/deliver/' + id) }
}
