/**
 * 收藏 API 空壳
 *
 * ⚠️ 后端收藏模块接口尚未就绪，当前仅预留模块与方法签名。
 * 页面数据使用本地占位（见 views/Favorites.vue）。
 *
 * 预期接口（对照前端使用）：
 *   GET    /api/favorite/list       收藏商品列表
 *   POST   /api/favorite/toggle     收藏 / 取消收藏（传 productId）
 *   DELETE /api/favorite/{productId} 取消收藏
 */
import { get, del } from './request'
import type { Result } from './types'

export const favoriteApi = {
  /** 收藏列表（未就绪，占位） */
  list(): Promise<Result<unknown>> {
    // TODO(P1): 接入 GET /api/favorite/list
    return get<unknown>('/favorite/list')
  },
  /** 取消收藏（未就绪，占位） */
  remove(_productId: string): Promise<Result<void>> {
    // TODO(P1): 接入 DELETE /api/favorite/{productId}
    return del<void>('/favorite/' + _productId)
  }
}
