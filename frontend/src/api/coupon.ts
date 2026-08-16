/**
 * 优惠券 API 空壳
 *
 * ⚠️ 后端营销/优惠券模块接口尚未就绪，当前仅预留模块与方法签名。
 * 页面数据使用本地占位（见 views/Coupons.vue / views/MyCoupons.vue）。
 *
 * 预期接口（对照前端使用）：
 *   GET  /api/coupon/center        领券中心（平台券 + 各店券）
 *   POST /api/coupon/receive/{id}  领取优惠券
 *   GET  /api/coupon/my            我的优惠券（可用/已用/已过期）
 */
import { get, post } from './request'
import type { Result } from './types'

export const couponApi = {
  /** 领券中心列表（未就绪，占位） */
  center(): Promise<Result<unknown>> {
    // TODO(P0): 接入 GET /api/coupon/center
    return get<unknown>('/coupon/center')
  },
  /** 领取优惠券（未就绪，占位） */
  receive(_id: string): Promise<Result<void>> {
    // TODO(P0): 接入 POST /api/coupon/receive/{id}
    return post<void>('/coupon/receive/' + _id)
  },
  /** 我的优惠券（未就绪，占位） */
  mine(): Promise<Result<unknown>> {
    // TODO(P0): 接入 GET /api/coupon/my
    return get<unknown>('/coupon/my')
  }
}
