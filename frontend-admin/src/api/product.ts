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
  /** 编辑商品信息（名称/描述/轮播图/详情/分类/品牌/主图 + SKU 增删改） */
  update(id: string | number, data: Record<string, unknown>): Promise<Result<void>> {
    return put<void>('/product/' + id, data)
  },
  /** 更新商品状态 */
  updateStatus(id: string | number, status: number): Promise<Result<void>> {
    return put<void>('/product/status/' + id, null, { params: { status } })
  },
  /**
   * 上传商品图片，返回可直接访问的相对 URL（形如 /uploads/2026/10/13/<uuid>.jpg）
   * ⚠️ 不要手动设置 Content-Type —— axios 传 FormData 会自动补 multipart boundary
   */
  uploadImage(file: File): Promise<Result<string>> {
    const formData = new FormData()
    formData.append('file', file)
    return post<string>('/upload/image', formData, { timeout: 60000 })
  }
}