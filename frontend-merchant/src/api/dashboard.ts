import { get } from '@/api/request'
import type { DashboardStats, MerchantStatsVO, OrderVO, PageResult, ProductVO, SalesTrendPoint } from './types'

/**
 * 获取仪表盘统计数据（使用商家专用 API）
 */
export const getDashboardStats = async (): Promise<DashboardStats> => {
  try {
    const [productRes, statsRes] = await Promise.all([
      get<PageResult<ProductVO>>('/merchant/product/page', { params: { page: 1, pageSize: 1 } }),
      get<MerchantStatsVO>('/merchant/stats/dashboard')
    ])
    const totalProducts = Number(productRes.data?.total) || 0
    const stats = statsRes.data
    return {
      totalProducts,
      onShelf: 0,
      totalOrders: Number(stats.totalOrders) || 0,
      pendingShip: Number(stats.pendingOrders) || 0
    }
  } catch (e) {
    console.error('获取仪表盘统计失败:', e)
    return { totalProducts: 0, onShelf: 0, totalOrders: 0, pendingShip: 0 }
  }
}

/**
 * 获取销售趋势（基于订单数据按日期分组）
 * @param days 天数，默认7天
 */
export const getSalesTrend = async (days = 7): Promise<SalesTrendPoint[]> => {
  try {
    const res = await get<PageResult<OrderVO>>('/merchant/order/page', { params: { page: 1, pageSize: 999 } })
    const orders = res.data?.list || []

    const dates: string[] = []
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date()
      d.setDate(d.getDate() - i)
      dates.push(d.toISOString().slice(0, 10))
    }

    const dailyMap: Record<string, number> = {}
    orders
      .filter(o => o.status >= 1 && o.status < 5)
      .forEach(o => {
        const date = o.createTime?.slice(0, 10)
        if (date) {
          dailyMap[date] = (dailyMap[date] || 0) + Number(o.totalAmount || 0)
        }
      })

    return dates.map(d => ({
      date: d,
      amount: dailyMap[d] || 0
    }))
  } catch (e) {
    console.error('获取销售趋势失败:', e)
    return []
  }
}