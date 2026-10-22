/**
 * 评价管理 API（管理后台）
 *
 * 后端：`/api/admin/review/*`
 */
import { get, put, del } from './request'
import type { PageResult, Result } from './types'

/** 评价视图（管理端可见昵称与全部状态） */
export interface AdminReviewVO {
  id: string
  orderItemId: string
  productId: string
  productName: string
  productImage: string
  /** 状态：0隐藏 1显示 */
  status: number
  rating: number
  content: string
  images: string[]
  anonymous: boolean
  nickname?: string
  reply?: string
  replyTime?: string
  createTime: string
}

export const reviewAdminApi = {
  /** 评价分页（含隐藏评价） */
  page(params: {
    page: number
    pageSize: number
    status?: number
    keyword?: string
  }): Promise<Result<PageResult<AdminReviewVO>>> {
    return get<PageResult<AdminReviewVO>>('/admin/review/page', { params })
  },
  /** 修改状态：0隐藏 1显示 */
  updateStatus(id: string, status: number): Promise<Result<void>> {
    return put<void>('/admin/review/' + id + '/status', null, { params: { status } })
  },
  /** 删除评价 */
  remove(id: string): Promise<Result<void>> {
    return del<void>('/admin/review/' + id)
  },
  /** 商家回复 */
  reply(id: string, reply: string): Promise<Result<void>> {
    return put<void>('/admin/review/' + id + '/reply', null, { params: { reply } })
  }
}
