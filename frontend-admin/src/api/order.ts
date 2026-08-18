import { get } from './request'
import type { AdminOrderPageQuery, OrderVO, PageResult, Result } from './types'

/**
 * 管理后台订单接口
 *
 * ⚠️ 批次3 起对齐管理员端路径 /admin/order/*：
 *   - adminPage   → GET  /api/admin/order/page（全平台订单分页，多条件筛选）
 *   - adminDetail → GET  /api/admin/order/detail/{id}（订单详情，含商品清单/收货信息）
 * 原用户端 /order/my、/order/detail 等路径与本端无关，已移除。
 */
export const orderApi = {
  /** 管理员订单分页（status/shopId/keyword/startTime/endTime） */
  adminPage(params?: AdminOrderPageQuery): Promise<Result<PageResult<OrderVO>>> {
    return get<PageResult<OrderVO>>('/admin/order/page', { params })
  },
  /** 管理员订单详情 */
  adminDetail(id: string | number): Promise<Result<OrderVO>> {
    return get<OrderVO>('/admin/order/detail/' + id)
  }
}
