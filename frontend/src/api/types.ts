/**
 * 星创优品用户端 API 类型定义
 *
 * 字段对照后端：
 *   - com.shop.common.Result / PageResult
 *   - com.shop.model.vo.{ProductVO,OrderVO,CartVO}
 *   - com.shop.model.entity.{CategoryDO,UserDO}
 *   - com.shop.rag.RagHitVO
 *
 * ⚠️ Long→String 序列化：全局 Jackson 将 Long 统一序列化为字符串，
 * 故 id / parentId / shopId / total 等字段类型为 string。
 * 前端需要参与数值运算时请先 Number() 转换（比较用 == 或 Number() 后 ===）。
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

// ==================== 用户 ====================

/** 当前用户信息（对照 UserDO） */
export interface UserVO {
  id: string
  username: string
  /** 手机号 */
  phone: string
  email: string
  nickname: string
  avatar: string
  /** 状态：0正常 1停用 2注销中 3封禁中 */
  status: number
  createTime: string
  updateTime: string
}

/** 登录返回（Result<Map<String,Object>>，仅 token） */
export interface LoginResult {
  token: string
}

/** 用户角色编码列表（Result<List<String>>） */
export type RoleList = string[]

// ==================== 分类（对照 CategoryDO） ====================

export interface CategoryVO {
  id: string
  name: string
  /** 父分类ID，0 为顶级（Long→string） */
  parentId: string
  sort: number
  icon: string
  /** 状态：0正常 1隐藏 */
  status: number
  createTime: string
  updateTime: string
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

/** RAG 语义搜索命中（对照 RagHitVO） */
export interface RagHitVO {
  productId: string
  name: string
  image: string
  minPrice: number
  maxPrice: number
  /** 相关性分数 */
  score: number
  /** 内容摘要 */
  excerpt: string
}

/** rag-search 返回体（Result<Map<String,Object>>） */
export interface RagSearchResult {
  hits: RagHitVO[]
  total: number
  query: string
}

/** 商品分页查询参数 */
export interface ProductPageQuery {
  page?: number
  pageSize?: number
  keyword?: string
  categoryId?: string | number
  firstCategoryId?: string | number
}

// ==================== 购物车（对照 CartVO / CartDTO） ====================

/** 购物车商品项 */
export interface CartItemVO {
  id: string
  productId: string
  productName: string
  productImage: string
  skuId: string
  skuName: string
  price: number
  quantity: number
  subtotal: number
  /** 可用库存 */
  stock: number
  /** 是否选中 */
  checked: boolean
  /** 是否已下单：0未下单 1已下单 */
  isOrdered: number
  /** 是否有效（库存不足/下架时为 false） */
  valid: boolean
}

/** 加购入参（对照 CartDTO，后端仅取 skuId/quantity） */
export interface CartAddPayload {
  skuId: string
  quantity: number
}

// ==================== 订单（对照 OrderVO / OrderCreateDTO） ====================

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
  /** 状态：0待支付 1已支付 2已发货 3? 4已完成 5已取消 */
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

/** 订单防重令牌（Result<Map<String,String>>） */
export interface OrderTokenResult {
  token: string
}

/** 下单返回（Result<String>，值为订单 ID 的字符串） */
export type OrderCreateResult = string

/** 下单商品项（对照 OrderCreateDTO.OrderItemDTO） */
export interface OrderItemPayload {
  skuId: string
  quantity: number
}

/** 创建订单入参（对照 OrderCreateDTO） */
export interface OrderCreatePayload {
  token: string
  addressId?: string
  receiverName?: string
  receiverPhone?: string
  receiverAddress?: string
  remark?: string
  items: OrderItemPayload[]
}

/** 我的订单分页查询参数 */
export interface OrderPageQuery {
  page?: number
  pageSize?: number
  status?: number | string
}
