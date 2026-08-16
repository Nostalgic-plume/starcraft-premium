/**
 * 评价 API 空壳
 *
 * ⚠️ 后端评价模块接口尚未就绪，当前仅预留模块与方法签名。
 * 页面数据使用本地占位（见 views/MyReviews.vue / views/ReviewForm.vue）。
 *
 * 预期接口（对照前端使用）：
 *   GET  /api/review/my         我的评价列表
 *   POST /api/review            发表评价（评分 + 内容 + 图片）
 */
import { get, post } from './request'
import type { Result } from './types'

export const reviewApi = {
  /** 我的评价列表（未就绪，占位） */
  mine(): Promise<Result<unknown>> {
    // TODO(P0): 接入 GET /api/review/my
    return get<unknown>('/review/my')
  },
  /** 发表评价（未就绪，占位） */
  create(_data: unknown): Promise<Result<void>> {
    // TODO(P0): 接入 POST /api/review
    return post<void>('/review', _data)
  }
}
