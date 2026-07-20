import request from '@/api/request'

/** 商家端订单 API */
export const orderApi = {
  /** 获取本店订单列表 */
  myOrders(params) { return request.get('/order/my', { params }) },

  /** 获取订单详情 */
  detail(id) { return request.get('/order/detail/' + id) },

  /** 取消订单 */
  cancel(orderId) { return request.post('/order/cancel/' + orderId) },

  /** 确认收货 */
  receive(orderId) { return request.post('/order/receive/' + orderId) }
}
