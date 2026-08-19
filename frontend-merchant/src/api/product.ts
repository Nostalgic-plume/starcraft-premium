import { get, post, put } from '@/api/request'
import type { PageResult, ProductForm, ProductVO, Result } from './types'

/**
 * 商家端商品 API
 *
 * ⚠️ 本批仅做 TS 化，保留原 URL（用户端 /product/* 路径），
 * 当前未被任何视图引用（现有页面直接调 request 的 /merchant/product/* 或 /product/detail），
 * 批次2+ 页面重写时可评估清理或对齐商家端 /merchant/product/* 路径。
 */
export const productApi = {
  /** 商品分页 */
  page(params?: Record<string, unknown>): Promise<Result<PageResult<ProductVO>>> {
    return get<PageResult<ProductVO>>('/product/page', { params })
  },

  /** 商品详情 */
  detail(id: string | number): Promise<Result<ProductVO>> {
    return get<ProductVO>('/product/detail/' + id)
  },

  /** 新增商品 */
  add(data: ProductForm): Promise<Result<string>> {
    return post<string>('/product/add', data)
  },

  /** 更新商品状态 */
  updateStatus(id: string | number, status: number): Promise<Result<null>> {
    return put<null>('/product/status/' + id, null, { params: { status } })
  }
}