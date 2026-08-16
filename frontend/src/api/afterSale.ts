/**
 * 售后 API 空壳
 *
 * ⚠️ 后端售后模块接口尚未就绪，当前仅预留模块与方法签名。
 * 页面数据使用本地占位（见 views/AfterSales.vue / views/AfterSalesNew.vue）。
 *
 * 预期接口（对照前端使用）：
 *   GET  /api/after-sale/my    我的售后列表
 *   POST /api/after-sale       申请售后（类型 + 原因 + 金额）
 */
import { get, post } from './request'
import type { Result } from './types'

export const afterSaleApi = {
  /** 我的售后列表（未就绪，占位） */
  mine(): Promise<Result<unknown>> {
    // TODO(P1): 接入 GET /api/after-sale/my
    return get<unknown>('/after-sale/my')
  },
  /** 申请售后（未就绪，占位） */
  create(_data: unknown): Promise<Result<void>> {
    // TODO(P1): 接入 POST /api/after-sale
    return post<void>('/after-sale', _data)
  }
}
