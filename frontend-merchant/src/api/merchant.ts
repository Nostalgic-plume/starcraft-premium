import { get, post, put } from '@/api/request'
import type {
  DeliverPayload, OrderPageQuery, OrderVO, PageResult, ProductForm,
  ProductPageQuery, ProductVO, Result, ShopInfoVO
} from './types'

/**
 * 商家端 API（商家独立账号体系，对应 /api/merchant/*）
 *
 * ⚠️ shop_id 由后端从登录态注入（StpUtil.getLoginIdAsLong()），前端一律不传。
 * 能力限制：/merchant/product/page 支持 keyword + status 分页（均由后端过滤）；
 *           /merchant/order/page 仅支持 status 分页（无 keyword 参数）→ 订单页对 keyword 做前端过滤（见 Orders.vue）。
 */
export const merchantApi = {
  /** 获取当前商家信息（店铺名称/Logo，供头部展示） */
  getInfo(): Promise<Result<ShopInfoVO>> { return get<ShopInfoVO>('/merchant/info') },

  // ==================== 文件上传 ====================

  /**
   * 上传商品图片，返回可直接访问的相对 URL（形如 /uploads/2026/10/13/<uuid>.jpg）
   * <p>
   * ⚠️ 不要手动设置 Content-Type —— axios 传 FormData 时会自动补 multipart boundary，
   * 手设会丢失 boundary 导致后端解析失败。超时放宽到 60s，避免大图上传被 15s 默认值截断。
   * </p>
   */
  uploadImage(file: File): Promise<Result<string>> {
    const formData = new FormData()
    formData.append('file', file)
    return post<string>('/upload/image', formData, { timeout: 60000 })
  },

  // ==================== 商品管理 ====================

  /** 本店商品分页（keyword + status 均由后端过滤，分页与筛选一致） */
  productPage(params?: ProductPageQuery): Promise<Result<PageResult<ProductVO>>> {
    return get<PageResult<ProductVO>>('/merchant/product/page', { params })
  },

  /** 新增商品（自动绑定本店，需 product:add 权限） */
  productAdd(data: ProductForm): Promise<Result<string>> {
    return post<string>('/merchant/product/add', data)
  },

  /**
   * 编辑商品信息（名称 / 描述 / 轮播图 / 详情 / 分类 / 品牌 / 主图 + SKU 增删改）
   * 需 product:edit 权限；归属校验由后端按 shop_id 强制（非本店返回「无权操作该商品」）
   */
  productUpdate(id: string | number, data: ProductForm): Promise<Result<null>> {
    return put<null>('/merchant/product/' + id, data)
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