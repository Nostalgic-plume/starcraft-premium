/**
 * 地址管理 API 空壳
 *
 * ⚠️ 后端地址模块接口尚未就绪，当前仅预留模块与方法签名。
 * 页面数据使用本地占位（见 views/Address.vue），接入后替换为真实调用。
 *
 * 预期接口（对照前端使用）：
 *   GET    /api/address/list       地址列表
 *   POST   /api/address            新增地址
 *   PUT    /api/address/{id}       编辑地址
 *   DELETE /api/address/{id}       删除地址
 */
import { get, post, put, del } from './request'
import type { Result } from './types'

// TODO(P0): 后端地址模块就绪后，在 types.ts 补充 AddressVO 并启用下列方法
// export interface AddressVO {
//   id: string
//   receiverName: string
//   receiverPhone: string
//   detail: string
//   isDefault: number // 0=否 1=是
// }

export const addressApi = {
  /** 地址列表（未就绪，占位） */
  list(): Promise<Result<unknown>> {
    // TODO(P0): 接入 GET /api/address/list
    return get<unknown>('/address/list')
  },
  /** 新增地址（未就绪，占位） */
  add(_data: unknown): Promise<Result<void>> {
    // TODO(P0): 接入 POST /api/address
    return post<void>('/address', _data)
  },
  /** 编辑地址（未就绪，占位） */
  update(_id: string, _data: unknown): Promise<Result<void>> {
    // TODO(P0): 接入 PUT /api/address/{id}
    return put<void>('/address/' + _id, _data)
  },
  /** 删除地址（未就绪，占位） */
  remove(_id: string): Promise<Result<void>> {
    // TODO(P0): 接入 DELETE /api/address/{id}
    return del<void>('/address/' + _id)
  }
}
