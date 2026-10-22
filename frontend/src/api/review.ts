/**
 * 商品评价 API
 *
 * 后端：`/api/review/*`
 * 发表评价只需传 orderItemId，商品 / 用户 / 订单归属全部由服务端反查校验。
 */
import { get, post } from './request'
import type { PageResult, Result } from './types'

/** 评价视图 */
export interface ReviewVO {
  id: string
  orderItemId: string
  productId: string
  productName: string
  productImage: string
  rating: number
  content: string
  images: string[]
  anonymous: boolean
  nickname?: string
  reply?: string
  replyTime?: string
  createTime: string
}

/** 发表评价参数 */
export interface ReviewCreatePayload {
  orderItemId: string
  rating: number
  content?: string
  images?: string[]
  isAnonymous?: number
}

export const reviewApi = {
  /** 我的评价列表 */
  mine(): Promise<Result<ReviewVO[]>> {
    return get<ReviewVO[]>('/review/my')
  },
  /** 商品评价列表（公开） */
  product(productId: string, page = 1, pageSize = 10): Promise<Result<PageResult<ReviewVO>>> {
    return get<PageResult<ReviewVO>>('/review/product/' + productId, { params: { page, pageSize } })
  },
  /** 发表评价 */
  create(data: ReviewCreatePayload): Promise<Result<void>> {
    return post<void>('/review', data)
  }
}
