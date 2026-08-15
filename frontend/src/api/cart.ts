import { del, get, post, put } from './request'
import type { CartAddPayload, CartItemVO, Result } from './types'

export const cartApi = {
  list(): Promise<Result<CartItemVO[]>> {
    return get<CartItemVO[]>('/cart/list')
  },
  add(data: CartAddPayload): Promise<Result<void>> {
    return post<void>('/cart/add', data)
  },
  updateQuantity(skuId: string | number, quantity: number): Promise<Result<void>> {
    return put<void>('/cart/quantity/' + skuId, null, { params: { quantity } })
  },
  remove(skuId: string | number): Promise<Result<void>> {
    return del<void>('/cart/remove/' + skuId)
  },
  check(skuId: string | number, checked: boolean): Promise<Result<void>> {
    return put<void>('/cart/check/' + skuId, null, { params: { checked } })
  }
}
