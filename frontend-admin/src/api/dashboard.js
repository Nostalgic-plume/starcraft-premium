import request from './request'

export const dashboardApi = {
  getStats() {
    return request.get('/admin/stats/dashboard')
  },
  getSalesTrend(days = 7) {
    return request.get('/admin/stats/sales-trend', { params: { days } })
  },
  getProductSales() {
    return request.get('/admin/stats/product-sales')
  },
  getUserGrowth() {
    return request.get('/admin/stats/user-growth')
  },
  getOrderDistribution() {
    return request.get('/admin/stats/order-distribution')
  }
}
