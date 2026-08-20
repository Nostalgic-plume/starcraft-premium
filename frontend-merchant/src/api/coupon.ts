import { get, post, put } from '@/api/request'
import type { PageResult, Result } from './types'

/**
 * 商家端优惠券 API（空壳，未接后端）
 *
 * ⚠️ 后端 `/api/merchant/coupon/*` 接口尚未实现，本模块仅预留方法签名与类型，
 *    页面当前用静态占位数据（Coupons.vue），不调用本模块。
 * TODO(占位): 后端补齐本店券 CRUD / 发券接口后接通。
 *
 * shop_id 由后端从登录态注入（StpUtil.getLoginIdAsLong()），前端一律不传。
 */

/** 本店优惠券列表项 */
export interface MerchantCouponVO {
  /** Long→string */
  id: string
  name: string
  /** 面额（元） */
  amount: number
  /** 使用门槛（元），0 为无门槛 */
  minAmount: number
  /** 发行量 */
  total: number
  received: number
  used: number
  startDate: string
  endDate: string
  /** 0生效中 1已停用 2已过期 */
  status: number
}

/** 本店优惠券分页查询参数 */
export interface CouponPageQuery {
  page?: number
  pageSize?: number
  /** 券状态（0-2） */
  status?: number | string
}

/** 新建优惠券入参 */
export interface CouponCreatePayload {
  name: string
  amount: number
  minAmount: number
  total: number
  startDate: string
  endDate: string
}

/** 发券入参 */
export interface CouponIssuePayload {
  /** Long→string */
  couponId: string
  count: number
}

export const couponApi = {
  /** 本店优惠券分页（占位签名，未接后端） */
  page(params?: CouponPageQuery): Promise<Result<PageResult<MerchantCouponVO>>> {
    return get<PageResult<MerchantCouponVO>>('/merchant/coupon/page', { params })
  },

  /** 新建优惠券（占位签名，未接后端） */
  create(data: CouponCreatePayload): Promise<Result<string>> {
    return post<string>('/merchant/coupon/add', data)
  },

  /** 更新券状态（停用/启用；占位签名，未接后端） */
  updateStatus(id: string | number, status: number): Promise<Result<null>> {
    return put<null>('/merchant/coupon/status/' + id, null, { params: { status } })
  },

  /** 发券（占位签名，未接后端） */
  issue(data: CouponIssuePayload): Promise<Result<null>> {
    return post<null>('/merchant/coupon/issue', data)
  }
}
