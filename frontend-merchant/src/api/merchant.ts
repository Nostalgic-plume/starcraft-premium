import { get } from '@/api/request'
import type { Result, ShopInfoVO } from './types'

/** 商家端 API（商家独立账号体系，对应 /api/merchant/*） */
export const merchantApi = {
  /** 获取当前商家信息（店铺名称/Logo，供头部展示） */
  getInfo(): Promise<Result<ShopInfoVO>> { return get<ShopInfoVO>('/merchant/info') }
}