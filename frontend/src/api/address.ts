/**
 * 地址管理 API
 *
 * 后端接口：`/api/address/*`（需用户登录，userId 由服务端从会话注入）
 * 归属隔离与默认地址唯一性均由后端保证，前端只负责收集与展示。
 */
import { get, post, put, del } from './request'
import type { Result } from './types'

/** 收货地址（后端 AddressDO 的对外字段） */
export interface AddressVO {
  /** 雪花 ID 被 Jackson 序列化为字符串，避免 JS 精度丢失 */
  id: string
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detail: string
  /** 0=否 1=是 */
  isDefault: number
}

/** 新增 / 编辑地址的请求体 */
export interface AddressPayload {
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detail: string
  isDefault?: number
}

export const addressApi = {
  /** 当前用户的地址列表（默认地址置顶） */
  list(): Promise<Result<AddressVO[]>> {
    return get<AddressVO[]>('/address/list')
  },
  /** 新增地址（首条由后端自动设为默认） */
  add(data: AddressPayload): Promise<Result<number>> {
    return post<number>('/address', data)
  },
  /** 编辑地址 */
  update(id: string, data: AddressPayload): Promise<Result<void>> {
    return put<void>('/address/' + id, data)
  },
  /** 删除地址 */
  remove(id: string): Promise<Result<void>> {
    return del<void>('/address/' + id)
  },
  /** 设为默认地址 */
  setDefault(id: string): Promise<Result<void>> {
    return put<void>('/address/' + id + '/default')
  }
}
