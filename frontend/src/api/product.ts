import { get, post, put } from './request'
import type { PageResult, ProductPageQuery, ProductVO, RagSearchResult, Result } from './types'

export const productApi = {
  /** 商品分页（categoryId / firstCategoryId / keyword） */
  page(params?: ProductPageQuery): Promise<Result<PageResult<ProductVO>>> {
    return get<PageResult<ProductVO>>('/product/page', { params })
  },
  detail(id: string | number): Promise<Result<ProductVO>> {
    return get<ProductVO>('/product/detail/' + id)
  },
  add(data: unknown): Promise<Result<string>> {
    return post<string>('/product/add', data)
  },
  updateStatus(id: string | number, status: number): Promise<Result<void>> {
    return put<void>('/product/status/' + id, null, { params: { status } })
  },
  /** 语义搜索（RAG 向量检索） */
  ragSearch(params: { q: string; topK?: number }): Promise<Result<RagSearchResult>> {
    return get<RagSearchResult>('/product/rag-search', { params })
  }
}
