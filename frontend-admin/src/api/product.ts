import { get, post, put } from './request'
import type { PageResult, ProductPageQuery, ProductVO, Result } from './types'

export const productApi = {
  /** 商品分页 */
  page(params?: ProductPageQuery): Promise<Result<PageResult<ProductVO>>> {
    return get<PageResult<ProductVO>>('/product/page', { params })
  },
  /** 商品详情 */
  detail(id: string | number): Promise<Result<ProductVO>> {
    return get<ProductVO>('/product/detail/' + id)
  },
  /** 新增商品 */
  add(data: Record<string, unknown>): Promise<Result<void>> {
    return post<void>('/product/add', data)
  },
  /** 更新商品状态 */
  updateStatus(id: string | number, status: number): Promise<Result<void>> {
    return put<void>('/product/status/' + id, null, { params: { status } })
  }
}