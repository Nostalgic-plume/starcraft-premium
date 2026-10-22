/**
 * 商品收藏 API
 *
 * 后端：`/api/favorite/*`（需用户登录，userId 由服务端从会话注入）
 */
import { get, post, del } from './request'
import type { ProductVO, Result } from './types'

export const favoriteApi = {
  /** 收藏商品列表（按收藏时间倒序，后端跳过已下架 / 已删除商品） */
  list(): Promise<Result<ProductVO[]>> {
    return get<ProductVO[]>('/favorite/list')
  },
  /** 是否已收藏 */
  check(productId: string): Promise<Result<boolean>> {
    return get<boolean>('/favorite/check/' + productId)
  },
  /** 收藏总数 */
  count(): Promise<Result<number>> {
    return get<number>('/favorite/count')
  },
  /** 收藏（幂等） */
  add(productId: string): Promise<Result<void>> {
    return post<void>('/favorite/' + productId)
  },
  /** 取消收藏 */
  remove(productId: string): Promise<Result<void>> {
    return del<void>('/favorite/' + productId)
  }
}
