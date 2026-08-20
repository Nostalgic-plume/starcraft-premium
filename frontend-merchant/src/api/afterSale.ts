import { get, post } from '@/api/request'
import type { PageResult, Result } from './types'

/**
 * 商家端售后 API（空壳，未接后端）
 *
 * ⚠️ 后端 `/api/merchant/after-sale/*` 接口尚未实现，本模块仅预留方法签名与类型，
 *    页面当前用静态占位数据（AfterSales.vue），不调用本模块。
 * TODO(占位): 后端补齐本店售后单分页 / 审核（同意/拒绝）接口后接通。
 *
 * shop_id 由后端从登录态注入（StpUtil.getLoginIdAsLong()），前端一律不传。
 */

/** 本店售后单列表项 */
export interface MerchantAfterSaleVO {
  /** Long→string */
  id: string
  no: string
  orderNo: string
  product: string
  /** 1仅退款 2退货退款 3换货 */
  type: number
  amount: number
  /** 0待审核 1待退款 2已退款 3已完成 4已驳回 */
  status: number
  reason: string
  createTime: string
}

/** 本店售后单分页查询参数 */
export interface AfterSalePageQuery {
  page?: number
  pageSize?: number
  /** 售后类型（1-3） */
  type?: number | string
  /** 售后状态（0-4） */
  status?: number | string
}

/** 售后审核入参 */
export interface AfterSaleAuditPayload {
  /** Long→string */
  afterSaleId: string
  /** true=同意 false=拒绝 */
  approve: boolean
  /** 审核备注（可选） */
  remark?: string
}

export const afterSaleApi = {
  /** 本店售后单分页（占位签名，未接后端） */
  page(params?: AfterSalePageQuery): Promise<Result<PageResult<MerchantAfterSaleVO>>> {
    return get<PageResult<MerchantAfterSaleVO>>('/merchant/after-sale/page', { params })
  },

  /** 售后审核（同意/拒绝；占位签名，未接后端） */
  audit(data: AfterSaleAuditPayload): Promise<Result<null>> {
    return post<null>('/merchant/after-sale/audit', data)
  }
}
