import { get } from '@/api/request'
import type { PageResult, Result } from './types'

/**
 * 商家端库存预警 API（空壳，未接后端）
 *
 * ⚠️ 后端 `/api/merchant/stock/alert` 接口尚未实现，本模块仅预留方法签名与类型，
 *    页面当前用静态占位数据（StockAlert.vue），不调用本模块。
 * TODO(占位): 后端补齐本店低库存查询接口后接通，并按此数据驱动「本店看板」导航角标。
 *
 * shop_id 由后端从登录态注入（StpUtil.getLoginIdAsLong()），前端一律不传。
 */

/** 低库存商品预警项 */
export interface StockAlertVO {
  /** Long→string */
  id: string
  name: string
  skuName: string
  /** 当前总库存 */
  stock: number
  /** 安全阈值 */
  threshold: number
  /** 0严重（≤0.5×阈值） 1一般（≤阈值） */
  level: number
}

/** 库存预警查询参数 */
export interface StockAlertPageQuery {
  page?: number
  pageSize?: number
}

export const stockApi = {
  /** 本店低库存商品列表（占位签名，未接后端） */
  alertList(params?: StockAlertPageQuery): Promise<Result<PageResult<StockAlertVO>>> {
    return get<PageResult<StockAlertVO>>('/merchant/stock/alert', { params })
  }
}
