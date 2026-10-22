/**
 * 分类 / 品牌主数据 API（管理后台）
 *
 * 后端：`/api/admin/category/*`、`/api/admin/brand/*`
 * 雪花 ID 由 Jackson 序列化为字符串，类型统一用 string。
 */
import { get, post, put, del } from './request'
import type { PageResult, Result } from './types'

/** 分类（两级：parentId 为 '0' 表示一级分类） */
export interface CategoryVO {
  id: string
  name: string
  parentId: string
  sort: number
  icon?: string
  status: number
}

export interface CategoryPayload {
  name: string
  parentId?: string
  sort?: number
  icon?: string
  status?: number
}

/** 品牌 */
export interface BrandVO {
  id: string
  name: string
  logo?: string
  description?: string
  sort: number
  status: number
}

export interface BrandPayload {
  name: string
  logo?: string
  description?: string
  sort?: number
  status?: number
}

export const categoryApi = {
  /** 分类列表（含停用分类） */
  list(): Promise<Result<CategoryVO[]>> {
    return get<CategoryVO[]>('/admin/category/list')
  },
  add(data: CategoryPayload): Promise<Result<number>> {
    return post<number>('/admin/category', data)
  },
  update(id: string, data: CategoryPayload): Promise<Result<void>> {
    return put<void>('/admin/category/' + id, data)
  },
  remove(id: string): Promise<Result<void>> {
    return del<void>('/admin/category/' + id)
  }
}

export const brandApi = {
  /** 品牌分页（含停用品牌，支持名称搜索） */
  page(params: { page: number; pageSize: number; keyword?: string }): Promise<Result<PageResult<BrandVO>>> {
    return get<PageResult<BrandVO>>('/admin/brand/page', { params })
  },
  add(data: BrandPayload): Promise<Result<number>> {
    return post<number>('/admin/brand', data)
  },
  update(id: string, data: BrandPayload): Promise<Result<void>> {
    return put<void>('/admin/brand/' + id, data)
  },
  remove(id: string): Promise<Result<void>> {
    return del<void>('/admin/brand/' + id)
  }
}
