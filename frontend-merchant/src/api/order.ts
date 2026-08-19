import { get, post } from '@/api/request'
import type { OrderPageQuery, OrderVO, PageResult, Result } from './types'

/**
 * 商家端订单 API
 *
 * ⚠️ 本批仅做 TS 化，保留原 URL（用户端 /order/my、/order/detail 等路径），
 * 当前未被任何视图引用（现有页面直接调 request.get('/merchant/order/page')），
 * 批次2+ 页面重写时可评估清理或对齐商家端 /merchant/order/* 路径。
 */
export const orderApi = {
  /** 获取本店订单列表 */
  myOrders(params?: OrderPageQuery): Promise<Result<PageResult<OrderVO>>> {
    return get<PageResult<OrderVO>>('/order/my', { params })
  },

  /** 获取订单详情 */
  detail(id: string | number): Promise<Result<OrderVO>> {
    return get<OrderVO>('/order/detail/' + id)
  },

  /** 取消订单 */
  cancel(orderId: string | number): Promise<Result<null>> {
    return post<null>('/order/cancel/' + orderId)
  },

  /** 确认收货 */
  receive(orderId: string | number): Promise<Result<null>> {
    return post<null>('/order/receive/' + orderId)
  }
}