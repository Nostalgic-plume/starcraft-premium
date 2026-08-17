import { get, post } from './request'
import type { AdminOrderPageQuery, OrderVO, PageResult, Result } from './types'

export const orderApi = {
  myOrders(params?: AdminOrderPageQuery): Promise<Result<PageResult<OrderVO>>> {
    return get<PageResult<OrderVO>>('/order/my', { params })
  },
  detail(id: string | number): Promise<Result<OrderVO>> {
    return get<OrderVO>('/order/detail/' + id)
  },
  cancel(id: string | number): Promise<Result<void>> {
    return post<void>('/order/cancel/' + id)
  },
  receive(id: string | number): Promise<Result<void>> {
    return post<void>('/order/receive/' + id)
  },
  deliver(id: string | number): Promise<Result<void>> {
    return post<void>('/order/deliver/' + id)
  }
}