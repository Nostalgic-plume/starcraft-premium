/**
 * 星创优品商家中心 API 类型定义
 *
 * 字段对照后端：
 *   - com.shop.common.Result / com.shop.common.PageResult
 *   - com.shop.model.vo.{DashboardStatsVO, OrderVO, ProductVO}
 *   - com.shop.model.entity.CategoryDO / ShopDO
 *   - com.shop.model.dto.{ShopLoginDTO, ProductDTO}
 *   - MerchantController（/api/merchant/*）：login/info/stats/dashboard/order/page/order/detail/order/deliver/product/page/product/add/product/status
 *
 * ⚠️ Long→String 序列化：全局 Jackson 将 Long 统一序列化为字符串，
 * 故 id / shopId / userId / total / totalOrders 等字段类型为 string。
 * 前端参与数值运算时先 Number() 转换（比较用 == 或 Number() 后 ===）。
 */

// ==================== 统一返回结构 ====================

/** 统一返回结构（对照 com.shop.common.Result） */
export interface Result<T = unknown> {
  /** 状态码：200 成功 */
  code: number
  /** 提示信息 */
  msg: string
  /** 业务数据 */
  data: T
}

/** 分页返回结构（对照 com.shop.common.PageResult） */
export interface PageResult<T = unknown> {
  /** 当前页码 */
  page: number
  /** 每页条数 */
  pageSize: number
  /** 总记录数（Long 序列化为字符串，需 Number() 后参与分页） */
  total: string
  /** 总页数 */
  pages: number
  /** 数据列表 */
  list: T[]
}

// ==================== 商家信息 ====================

/** 当前商家信息（对照 ShopServiceImpl.getCurrentShopInfo 的 Map；可空字段后端兜底为空串） */
export interface ShopInfoVO {
  /** Long→string */
  shopId: string
  username: string
  shopName: string
  shopLogo: string
  shopDesc: string
  contactName: string
  contactPhone: string
}

// ==================== 商品（对照 ProductVO / SkuVO / ProductDTO） ====================

/** 商品 SKU */
export interface SkuVO {
  /** Long→string */
  id: string
  name: string
  /** 规格值 JSON */
  specs: string
  price: number
  originalPrice: number
  stock: number
  image: string
}

/** 商品详情 / 商家商品列表项（对照 ProductVO） */
export interface ProductVO {
  /** Long→string */
  id: string
  name: string
  description: string
  /** Long→string */
  categoryId: string
  categoryName: string
  brandId: string
  brandName: string
  mainImage: string
  images: string[]
  detail: string
  /** 最低价 */
  minPrice: number
  /** 最高价 */
  maxPrice: number
  totalStock: number
  soldCount: number
  /** 状态：0=待审核 1=上架 2=下架 */
  status: number
  skuList: SkuVO[]
}

/** 商家商品列表查询参数（对应 /merchant/product/page） */
export interface ProductPageQuery {
  page?: number
  pageSize?: number
  keyword?: string
  /** 状态筛选：0=待审核 1=在售 2=下架。商家端/管理端生效；用户端强制仅返回上架商品 */
  status?: number
}

/** 新增/编辑商品 SKU 入参（对照 ProductDTO.SkuDTO） */
export interface SkuFormItem {
  /** SKU ID：仅编辑时由前端回传，标识要更新的已有 SKU；新增时留空（后端走 INSERT） */
  id?: string
  name: string
  specs?: string
  price: number
  originalPrice?: number
  stock: number
  image?: string
}

/** 新增商品入参（对照 ProductDTO；categoryId 为 Long→string） */
export interface ProductForm {
  name: string
  description?: string
  /** Long→string */
  categoryId: string | number
  brandId?: string | number
  mainImage?: string
  images?: string[]
  detail?: string
  skuList: SkuFormItem[]
}

// ==================== 订单（对照 OrderVO） ====================

/** 订单商品快照 */
export interface OrderItemVO {
  /** Long→string */
  skuId: string
  productName: string
  skuName: string
  productImage: string
  price: number
  quantity: number
  subtotal: number
}

/** 订单详情 / 商家订单列表项（对照 OrderVO） */
export interface OrderVO {
  /** Long→string */
  id: string
  orderNo: string
  /** Long→string */
  shopId: string
  /** Long→string */
  userId: string
  totalAmount: number
  actualAmount: number
  discountAmount: number
  freight: number
  /** 状态：0待支付 1已支付 2已发货 3已收货 4已完成 5已取消 6已退款 */
  status: number
  statusText: string
  receiverName: string
  receiverPhone: string
  receiverAddress: string
  createTime: string
  payTime: string | null
  deliverTime: string | null
  receiveTime: string | null
  logisticsNo: string
  remark: string
  items: OrderItemVO[]
}

/** 商家订单分页查询参数（对应 /merchant/order/page） */
export interface OrderPageQuery {
  page?: number
  pageSize?: number
  /** 订单状态（0-6） */
  status?: number | string
}

/** 发货入参（对照 MerchantController.deliver 的 Map body） */
export interface DeliverPayload {
  /** Long→string */
  orderId: string | number
  logisticsNo: string
}

// ==================== 控制台统计（对照 DashboardStatsVO） ====================

/** 商家控制台概览（对照 DashboardStatsVO，本店数据） */
export interface MerchantStatsVO {
  /** Long→string */
  totalUsers: string
  /** Long→string */
  totalProducts: string
  /** Long→string */
  totalOrders: string
  /** Double 保留数值 */
  totalSales: number
  /** Long→string（待发货订单数） */
  pendingOrders: string
}

// ==================== 前端组装结构（非后端直接返回） ====================

/** 订单状态分布项（前端从订单列表按 status 分组组装） */
export interface OrderDistItem {
  status: number
  cnt: number
}

/** 每日订单数点（前端按 createTime 日期分组组装） */
export interface DailyOrdersPoint {
  /** MM-DD */
  date: string
  count: number
}

/**
 * 本店商品销量排行项（前端从商品列表 soldCount 组装）
 * ⚠️ 商家端 /merchant/product/page 无 totalSales 字段，排行统一用 soldCount
 */
export interface ProductRankItem {
  name: string
  soldCount: number
}