import request from './request'

export const orderApi = {
  getToken() { return request.get('/order/token') },
  create(data) { return request.post('/order/create', data) },
  cancel(id) { return request.post('/order/cancel/' + id) },
  receive(id) { return request.post('/order/receive/' + id) },
  detail(id) { return request.get('/order/detail/' + id) },
  myOrders(params) { return request.get('/order/my', { params }) },
  // 模拟支付：传 orderNo（字符串），通过 query param 传递
  mockPay(orderNo) {
    return request.post('/pay/mock-pay', null, { params: { orderNo, payType: 1 } })
  }
}
