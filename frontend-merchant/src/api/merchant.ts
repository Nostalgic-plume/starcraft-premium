import { get, post, put } from '@/api/request'
import type {
  DeliverPayload, OrderPageQuery, OrderVO, PageResult, ProductForm,
  ProductPageQuery, ProductVO, Result, ShopInfoVO
} from './types'

/**
 * 商家端 API（商家独立账号体系，对应 /api/merchant/*）
 *
 * ⚠️ shop_id 由后端从登录态注入（StpUtil.getLoginIdAsLong()），前端一律不传。
 * 能力限制：/merchant/product/page 仅支持 keyword 分页（无 status 参数）；
 *           /merchant/order/page 仅支持 status 分页（无 keyword 参数）。
 *           页面侧对后端不支持的筛选条件做前端过滤（见 Products.vue / Orders.vue）。
 */
export const merchantApi = {
  /** 获取当前商家信息（店铺名称/Logo，供头部展示） */
  getInfo(): Promise<Result<ShopInfoVO>> { return get<ShopInfoVO>('/merchant/info') },

  // ==================== 商品管理 ====================

  /** 本店商品分页（仅 keyword 筛选；pageSize 上限内可拉全量供前端过滤） */
  productPage(params?: ProductPageQuery): Promise<Result<PageResult<ProductVO>>> {
    return get<PageResult<ProductVO>>('/merchant/product/page', { params })
  },

  /** 新增商品（自动绑定本店，需 product:add 权限） */
  productAdd(data: ProductForm): Promise<Result<string>> {
    return post<string>('/merchant/product/add', data)
  },

  /** 更新商品状态（0待审核 1在售 2下架；需 product:status 权限） */
  productUpdateStatus(id: string | number, status: number): Promise<Result<null>> {
    return put<null>('/merchant/product/status/' + id, null, { params: { status } })
  },

  // ==================== 订单管理 ====================

  /** 本店订单分页（status 0-6；列表项含 items 商品清单） */
  orderPage(params?: OrderPageQuery): Promise<Result<PageResult<OrderVO>>> {
    return get<PageResult<OrderVO>>('/merchant/order/page', { params })
  },

  /** 本店订单详情（含收货信息/商品清单/金额明细，归属校验） */
  orderDetail(id: string | number): Promise<Result<OrderVO>> {
    return get<OrderVO>('/merchant/order/detail/' + id)
  },

  /** 发货（仅 status=1 已支付订单；写物流单号并流转至已发货） */
  orderDeliver(data: DeliverPayload): Promise<Result<null>> {
    return post<null>('/merchant/order/deliver', data)
  }
}