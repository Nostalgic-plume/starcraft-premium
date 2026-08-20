import { get, post } from '@/api/request'
import type { PageResult, Result } from './types'

/**
 * 商家端评价 API（空壳，未接后端）
 *
 * ⚠️ 后端 `/api/merchant/review/*` 接口尚未实现，本模块仅预留方法签名与类型，
 *    页面当前用静态占位数据（Reviews.vue），不调用本模块。
 * TODO(占位): 后端补齐本店评价分页 / 商家回复接口后接通。
 *
 * shop_id 由后端从登录态注入（StpUtil.getLoginIdAsLong()），前端一律不传。
 */

/** 本店商品评价列表项 */
export interface MerchantReviewVO {
  /** Long→string */
  id: string
  product: string
  user: string
  /** 评分（1-5） */
  rating: number
  content: string
  /** 0待回复 1已回复 */
  status: number
  createTime: string
}

/** 本店评价分页查询参数 */
export interface ReviewPageQuery {
  page?: number
  pageSize?: number
  /** 回复状态（0-1） */
  status?: number | string
}

/** 商家回复入参 */
export interface ReviewReplyPayload {
  /** Long→string */
  reviewId: string
  replyContent: string
}

export const reviewApi = {
  /** 本店评价分页（占位签名，未接后端） */
  page(params?: ReviewPageQuery): Promise<Result<PageResult<MerchantReviewVO>>> {
    return get<PageResult<MerchantReviewVO>>('/merchant/review/page', { params })
  },

  /** 商家回复评价（占位签名，未接后端） */
  reply(data: ReviewReplyPayload): Promise<Result<null>> {
    return post<null>('/merchant/review/reply', data)
  }
}
