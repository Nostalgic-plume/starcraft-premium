import { get, post } from './request'
import type { OrderCreatePayload, OrderCreateResult, OrderPageQuery, OrderStatusResult, OrderTokenResult, OrderVO, PageResult, Result } from './types'

export const orderApi = {
  /** 下单防重令牌 */
  getToken(): Promise<Result<OrderTokenResult>> {
    return get<OrderTokenResult>('/order/token')
  },
  /** 创建订单（异步落单，返回 processingId 供轮询） */
  create(data: OrderCreatePayload): Promise<Result<OrderCreateResult>> {
    return post<OrderCreateResult>('/order/create', data)
  },
  /** 查询异步落单状态（前端轮询） */
  getStatus(processingId: string): Promise<Result<OrderStatusResult>> {
    return get<OrderStatusResult>('/order/status/' + processingId)
  },
  cancel(id: string | number): Promise<Result<void>> {
    return post<void>('/order/cancel/' + id)
  },
  receive(id: string | number): Promise<Result<void>> {
    return post<void>('/order/receive/' + id)
  },
  detail(id: string | number): Promise<Result<OrderVO>> {
    return get<OrderVO>('/order/detail/' + id)
  },
  /** 我的订单（分页 + 状态筛选） */
  myOrders(params?: OrderPageQuery): Promise<Result<PageResult<OrderVO>>> {
    return get<PageResult<OrderVO>>('/order/my', { params })
  },
  /** 模拟支付：传 orderNo（字符串），通过 query param 传递 */
  mockPay(orderNo: string): Promise<Result<void>> {
    return post<void>('/pay/mock-pay', null, { params: { orderNo, payType: 1 } })
  }
}
