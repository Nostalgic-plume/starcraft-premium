/**
 * 星创优品管理后台 API 类型定义
 *
 * 字段对照后端：
 *   - com.shop.common.Result / com.shop.common.PageResult
 *   - com.shop.model.vo.{DashboardStatsVO, SalesTrendVO, ProductSalesVO, OrderVO, ProductVO, DashboardAllVO}
 *   - com.shop.model.entity.{UserDO, AdminDO}
 *
 * ⚠️ Long→String 序列化：全局 Jackson 将 Long 统一序列化为字符串，
 * 故 id / shopId / userId / total / totalUsers 等字段类型为 string。
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

// ==================== 登录 ====================

/** 管理员登录返回（Result<Map<String,Object>>，仅取 token） */
export interface LoginResult {
  token: string
}

/** 登录入参（对照 AdminLoginDTO） */
export interface LoginPayload {
  username: string
  password: string
}

// ==================== 用户（对照 UserDO） ====================

/** 用户分页列表项 */
export interface UserVO {
  id: string
  username: string
  nickname: string
  phone: string
  email: string
  avatar: string
  /** 状态：0正常 1停用 2注销中 3封禁中 */
  status: number
  createTime: string
  updateTime: string
}

/** 用户状态变更入参（对照 AdminController 的 Map<String,Object>） */
export interface UserStatusPayload {
  /** 目标状态 */
  status: number
  /** 变更原因 */
  reason?: string
}

/** 用户分页查询参数 */
export interface UserPageQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: number | string
}

// ==================== 商品（对照 ProductVO / SkuVO） ====================

/** 商品 SKU */
export interface SkuVO {
  id: string
  name: string
  /** 规格值 JSON */
  specs: string
  price: number
  originalPrice: number
  stock: number
  image: string
}

/** 商品详情 / 商品列表项 */
export interface ProductVO {
  id: string
  name: string
  description: string
  categoryId: string
  categoryName: string
  brand: string
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

/** 商品分页查询参数 */
export interface ProductPageQuery {
  page?: number
  pageSize?: number
  keyword?: string
  categoryId?: string | number
  status?: number | string
}

// ==================== 订单（对照 OrderVO） ====================

/** 订单商品快照 */
export interface OrderItemVO {
  skuId: string
  productName: string
  skuName: string
  productImage: string
  price: number
  quantity: number
  subtotal: number
}

/** 订单详情 / 订单列表项 */
export interface OrderVO {
  id: string
  orderNo: string
  shopId: string
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

/** 管理后台订单分页查询参数 */
export interface AdminOrderPageQuery {
  page?: number
  pageSize?: number
  status?: number | string
  shopId?: string | number
  keyword?: string
  startTime?: string
  endTime?: string
}

// ==================== 控制台统计（对照 AdminStatsController + VO） ====================

/** 控制台概览（对照 DashboardStatsVO） */
export interface AdminStatsVO {
  /** Long→string */
  totalUsers: string
  totalProducts: string
  totalOrders: string
  /** Double 保留数值 */
  totalSales: number
  pendingOrders: string
}

/** 销售趋势 / 用户增长（对照 SalesTrendVO，amount/cnt 为 Object） */
export interface SalesTrendVO {
  date: string
  amount: number | string | null
  cnt: number | string | null
}

/** 商品销量排行（对照 ProductSalesVO） */
export interface ProductSalesVO {
  name: string
  /** Long→string */
  totalSales: string
}

/** 订单状态分布项（后端返回 List<Map>，含 status/cnt；cnt 为 COUNT(*) 即 Long→string） */
export interface OrderDistItem {
  status: number
  cnt: number | string
}

// ==================== 数据看板（对照 DashboardAllVO） ====================

/** 数据看板全量返回 */
export interface DashboardAllVO {
  /** 顶部概览卡片 */
  overview: DashboardOverviewVO
  /** GMV 概览 */
  gmvDaily: GmvDailyVO
  /** 订单状态分布 */
  orderStats: OrderStatsVO
  /** 分类销售排行 */
  categorySales: CategorySalesVO[]
  /** 商品销售排行 TOP */
  productTop: ProductTopVO[]
  /** 周趋势 */
  weeklyTrend: WeeklyTrendVO[]
  /** 用户活跃 */
  userActive: UserActiveVO
  /** 用户新增 / 留存 */
  userNewRetain: UserNewRetainVO
  /** 店铺日报 */
  shopDaily: ShopDailyVO
  /** GMV 日趋势（多天） */
  gmvTrend: GmvTrendPointVO[]
  /** 订单状态日趋势（多天） */
  orderTrend: OrderTrendPointVO[]
}

/** 顶部概览卡片 */
export interface DashboardOverviewVO {
  dt: string
  totalUsers: number
  totalProducts: number
  totalOrders: number
  totalGmv: number
  avgOrderPrice: number
}

/** GMV 概览 */
export interface GmvDailyVO {
  dt: string
  gmv: number
  orderCount: number
  paidOrderCount: number
  paidUserCount: number
  avgOrderAmount: number
}

/** 订单状态分布 */
export interface OrderStatsVO {
  dt: string
  totalOrders: number
  paidOrders: number
  shippedOrders: number
  completedOrders: number
  cancelledOrders: number
  refundOrders: number
  conversionRate: number
}

/** 分类销售排行 */
export interface CategorySalesVO {
  categoryName: string
  saleAmount: number
  saleCount: number
}

/** 商品销售排行 TOP */
export interface ProductTopVO {
  productName: string
  saleAmount: number
  saleQuantity: number
}

/** 周趋势 */
export interface WeeklyTrendVO {
  weekStart: string
  weekEnd: string
  gmv: number
  orderCount: number
  userCount: number
  gmvMom: number
}

/** 用户活跃 */
export interface UserActiveVO {
  dt: string
  dau: number
  orderUserCount: number
}

/** 用户新增 / 留存 */
export interface UserNewRetainVO {
  dt: string
  newUsers: number
  day1RetainUsers: number
  day1RetainRate: number
  day7RetainUsers: number
  day7RetainRate: number
  day30RetainUsers: number
  day30RetainRate: number
}

/** 店铺日报 */
export interface ShopDailyVO {
  dt: string
  shopName: string
  gmv: number
  orderCount: number
  productCount: number
}

/** GMV 日趋势点 */
export interface GmvTrendPointVO {
  dt: string
  gmv: number
  orderCount: number
  paidOrderCount: number
  paidUserCount: number
}

/** 订单状态日趋势点 */
export interface OrderTrendPointVO {
  dt: string
  totalOrders: number
  paidOrders: number
  shippedOrders: number
  completedOrders: number
  cancelledOrders: number
}