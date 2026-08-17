import { get } from './request'
import type { AdminStatsVO, OrderDistItem, ProductSalesVO, Result, SalesTrendVO } from './types'

export const dashboardApi = {
  /** 控制台概览统计 */
  getStats(): Promise<Result<AdminStatsVO>> {
    return get<AdminStatsVO>('/admin/stats/dashboard')
  },
  /** 销售额趋势 */
  getSalesTrend(days = 7): Promise<Result<SalesTrendVO[]>> {
    return get<SalesTrendVO[]>('/admin/stats/sales-trend', { params: { days } })
  },
  /** 商品销量排行 TOP10 */
  getProductSales(): Promise<Result<ProductSalesVO[]>> {
    return get<ProductSalesVO[]>('/admin/stats/product-sales')
  },
  /** 用户增长趋势（近30天） */
  getUserGrowth(): Promise<Result<SalesTrendVO[]>> {
    return get<SalesTrendVO[]>('/admin/stats/user-growth')
  },
  /** 订单状态分布 */
  getOrderDistribution(): Promise<Result<OrderDistItem[]>> {
    return get<OrderDistItem[]>('/admin/stats/order-distribution')
  }
}