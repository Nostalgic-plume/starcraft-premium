import { get } from './request'
import type { CategoryVO, Result } from './types'

export const categoryApi = {
  list(): Promise<Result<CategoryVO[]>> {
    return get<CategoryVO[]>('/category/list')
  }
}
