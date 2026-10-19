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

// ==================== RAG 索引管理（对照 IndexAdminController） ====================

/** MySQL FULLTEXT 索引健康检查结果 */
export interface FulltextIndexStatus {
  healthy: boolean
  testMatchCount: number
  message: string
}

/** 索引状态（GET /api/admin/rag/status） */
export interface RagIndexStatus {
  /** Qdrant 集合内已索引的商品数 */
  indexedCount: number
  /**
   * 最近一次全量重建的「结束」时间；未重建时是字符串「未重建」。仅进程内有效，后端重启后回到该值。
   * ⚠️ 只表示动作已结束、**不代表成功**：重建首步会先删掉 Qdrant 集合，
   * 失败早返回时集合已为空，必须结合 lastRebuildSuccess 判断成败。
   */
  lastRebuildTime: string
  /** 最近一次全量重建是否成功 */
  lastRebuildSuccess: boolean
  /** 最近一次重建的结果说明（失败原因 / 成功条数） */
  lastRebuildMessage: string
  /** FULLTEXT 索引健康检查 */
  fulltextIndex: FulltextIndexStatus
}

/** 触发重建的返回（POST /api/admin/rag/rebuild） */
export interface RagRebuildResult {
  message: string
}

/* ==================== 推荐子系统（/api/rec/**） ==================== */

/**
 * 说明：以下接口由推荐服务进程（8082）提供，经 vite 的 '/api/rec' 代理直达，
 * 商城后端并没有这些 Bean。
 *
 * ⚠️ 长整型标识（主键、商品标识、用户标识）一律为字符串：推荐服务与商城后端一样配置了
 *    Long → String 序列化。雪花 ID 是 19 位，超出 JS 安全整数范围，以数字下发会在前端丢精度。
 */

/** 推荐策略配置项（GET /api/rec/config） */
export interface RecConfigItem {
  id: string
  /** 配置键，如 rank.weight.cat */
  configKey: string
  configValue: string
  /** 值类型：int / decimal / string / json */
  valueType: string
  /** 配置分组：行为权值、窗口权值、召回、打分、缓存、开关 */
  groupName: string
  /** 配置说明 */
  remark: string
  /** 最后修改人 */
  operator: string
  updateTime: string
}

/** 固定坑位（GET /api/rec/slots） */
export interface RecFixedSlot {
  /** 主键；新增时不传 */
  id?: string
  /** 坑位位置，从 1 开始 */
  slotPosition: number | null
  /** 坑位商品标识 */
  itemId: string | null
  /** 生效开始时间 */
  startTime: string | null
  /** 生效结束时间 */
  endTime: string | null
  /** 状态：0 停用，1 启用 */
  status: number
  operator?: string
  createTime?: string
  updateTime?: string
}

/** 过滤规则（GET /api/rec/rules） */
export interface RecFilterRule {
  id?: string
  /** 规则类型：blacklist / member_only / region_limit / low_quality */
  ruleType: string
  /** 作用对象：item / category */
  targetType: string
  /** 对象取值：商品标识、类目标识或地区编码 */
  targetValue: string
  /** 状态：0 停用，1 启用 */
  status: number
  /** 规则说明 */
  remark: string
  operator?: string
  updateTime?: string
}

/** 画像偏好项 */
export interface RecPrefItem {
  /** 维度标识：类目标识或品牌标识 */
  dimId: string
  /** 偏好分 */
  score: number
}

/** 用户画像版本（GET /api/rec/profiles/{userId}/versions） */
export interface RecProfileVersion {
  id: string
  userId: string
  /** 版本号，取日期 yyyyMMdd */
  version: number
  /** 类目偏好列表 */
  categoryPref: RecPrefItem[]
  /** 品牌偏好列表 */
  brandPref: RecPrefItem[]
  /** 参与归纳的行为条数 */
  behaviorCount: number
  /** 状态：0 有效，1 历史版本 */
  status: number
  createTime: string
}

/** 指标定义（GET /api/rec/metrics/catalog） */
export interface RecMetricDefinition {
  name: string
  label: string
  unit: string
  /** 预警阈值；null 表示该指标不设阈值 */
  threshold: number | null
  /** true = 越大越好（低于阈值告警）；false = 越小越好（高于阈值告警） */
  higherIsBetter: boolean
  /**
   * 同一统计时段内多条明细的聚合方式
   *
   * ⚠️ 不可默认按求和展示：明细是「每次请求写一条」，同一小时内同一指标会有多条。
   * 比率类（覆盖率、命中率）求和会得出「85% + 90% = 175%」这类荒谬值，必须按 avg 聚合。
   */
  aggregation: 'sum' | 'avg'
}

/** 指标明细（GET /api/rec/metrics） */
export interface RecMetricRecord {
  id: string
  metricName: string
  metricDim: string | null
  metricValue: number
  /** 统计时间（写入时截到整点） */
  statTime: string
}

/** 商品索引重建状态（GET /api/rec/items/rebuild-status） */
export interface RecItemRebuildStatus {
  /** 是否正在重建 */
  rebuilding: boolean
  /** 最近一次重建的结束时间；从未重建时为 null */
  lastRebuildTime: string | null
  /** 最近一次重建是否成功 */
  lastRebuildSuccess: boolean
  /**
   * 结果说明；从未重建时为「尚未执行重建」
   *
   * ⚠️ 该文案表示「从未重建」而非「失败」——此时 lastRebuildSuccess 同样是 false，
   * 直接按布尔渲染会把「从未重建」显示成「失败」，属误报。
   */
  lastRebuildMessage: string
}