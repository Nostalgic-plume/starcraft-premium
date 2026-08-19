import { get } from '@/api/request'
import type { MerchantStatsVO, OrderVO, PageResult, ProductVO, Result } from './types'

/**
 * 商家端控制台数据 API
 *
 * 后端 MerchantController 仅提供 /merchant/stats/dashboard 一个统计接口，
 * 销售额趋势 / 订单分布 / 每日订单数 / 商品销量排行等图表数据由
 * Dashboard.vue 基于 order/page 与 product/page 的真实列表在组件内组装。
 * 本层只暴露底层数据拉取，由调用方用 Promise.allSettled 统一容错，
 * 单个接口失败不拖垮整屏（仿 M3 管理后台防御写法）。
 */
export const dashboardApi = {
  /** 控制台概览统计（本店：商品数/订单数/销售额/待发货） */
  getStats(): Promise<Result<MerchantStatsVO>> {
    return get<MerchantStatsVO>('/merchant/stats/dashboard')
  },

  /** 本店订单全量（pageSize 上限内），用于趋势 / 分布 / 每日订单组装 */
  getOrders(): Promise<Result<PageResult<OrderVO>>> {
    return get<PageResult<OrderVO>>('/merchant/order/page', { params: { page: 1, pageSize: 999 } })
  },

  /** 本店商品全量（pageSize 上限内），用于销量排行（soldCount）组装 */
  getProducts(): Promise<Result<PageResult<ProductVO>>> {
    return get<PageResult<ProductVO>>('/merchant/product/page', { params: { page: 1, pageSize: 999 } })
  }
}
