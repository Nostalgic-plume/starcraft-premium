/**
 * 用户行为埋点
 *
 * 采集浏览、加购、收藏、购买四类行为并上报，作为推荐子系统的唯一数据源。
 *
 * <b>上报约定</b>（对应详细设计 5.1.1）：
 * - 事件时间用带时区的 ISO 8601 格式，取行为实际发生时刻（不是上报时刻）；
 * - 每个事件带唯一 eventId，服务端以它做幂等去重，重试不会产生重复数据；
 * - 停留时长可选，仅浏览行为携带，用于服务端识别无效行为；
 * - 上报失败按上限重试，**绝不阻塞主流程**——埋点不能影响用户操作。
 *
 * <b>为什么用原生 fetch 而不是统一的 request 封装</b>：{@code shared/js/createRequest} 的响应
 * 拦截器会对任何失败弹出全局错误提示，并在 401 时清除登录态并跳转登录页。埋点失败弹窗会打扰
 * 用户，而埋点触发登出跳转更是灾难（用户正在浏览商品却被登出）。埋点属于「静默旁路」，
 * 必须绕开全局拦截器自行发请求——此处与 Home.vue 中已有的 traceClick 做法一致。
 */

/** 行为类型 */
export const BehaviorType = {
  VIEW: 'view',
  ADD_CART: 'add_cart',
  FAVORITE: 'favorite',
  PURCHASE: 'purchase',
} as const

export type BehaviorTypeValue = (typeof BehaviorType)[keyof typeof BehaviorType]

/**
 * 上报接口地址
 *
 * 走商城后端的 M-01 入口（推荐子系统采集模块内嵌部署在商城后端进程中）。
 * 路径不含 /api 前缀：此处用原生 fetch，不像 request 封装那样自带 baseURL。
 */
const REPORT_URL = '/api/rec/behavior/report'

/** 最大重试次数 */
const MAX_RETRY = 3

/** 重试基础间隔（毫秒），按次递增 */
const RETRY_BASE_DELAY = 300

/** 一次行为事件的上报载荷 */
interface BehaviorPayload {
  itemId: number | string
  eventType: BehaviorTypeValue
  eventTime: string
  eventId: string
  duration?: number
}

/**
 * 生成事件唯一标识
 *
 * 服务端以它做幂等去重，因此重试时必须复用同一个标识。
 *
 * 优先用 {@code crypto.randomUUID()}——浏览器原生的密码学随机源，碰撞概率可忽略；
 * 非安全上下文或旧浏览器下回退到「时间戳 + 随机数」。该标识只用于去重、不作为安全凭证，
 * 回退方案在语义上同样成立。
 */
function generateEventId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `evt_${crypto.randomUUID()}`
  }
  return `evt_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 取带时区的 ISO 8601 时间
 *
 * 手动拼 +08:00 而非用 toISOString()：后者输出 UTC 时间且带 Z 后缀，
 * 而服务端按东八区解释业务时间，用本地时间加时区后缀可避免跨日统计偏差。
 */
function localIsoTime(date: Date = new Date()): string {
  const pad = (n: number, len = 2) => String(n).padStart(len, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}+08:00`
  )
}

/**
 * 发送一次上报（不抛出异常）
 *
 * @param payload 上报载荷（重试时原样复用，保证 eventId 与 eventTime 不变）
 * @param attempt 当前已重试次数
 */
function send(payload: BehaviorPayload, attempt: number): void {
  const token = localStorage.getItem('token')
  fetch(REPORT_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { token } : {}),
    },
    body: JSON.stringify(payload),
  })
    .then((res) => {
      // 4xx 属于「请求本身无效」（参数非法、未登录），重试不会成功，直接放弃；
      // 5xx 与网络异常属于「服务暂时不可用」，按上限重试
      if (!res.ok && res.status >= 500) {
        scheduleRetry(payload, attempt)
      }
    })
    .catch(() => {
      // 网络不可达 / 请求被中断
      scheduleRetry(payload, attempt)
    })
}

/**
 * 安排一次重试
 *
 * @param payload 上报载荷
 * @param attempt 当前已重试次数
 */
function scheduleRetry(payload: BehaviorPayload, attempt: number): void {
  if (attempt >= MAX_RETRY) {
    // 超过上限后静默放弃：埋点丢失由服务端的特征覆盖率指标暴露，不在前端做告警
    return
  }
  setTimeout(() => send(payload, attempt + 1), RETRY_BASE_DELAY * (attempt + 1))
}

/**
 * 上报行为事件
 *
 * @param itemId    商品标识
 * @param eventType 行为类型
 * @param duration  停留时长（毫秒），仅浏览行为携带
 */
export function reportBehavior(
  itemId: number | string,
  eventType: BehaviorTypeValue,
  duration?: number,
): void {
  const numericId = Number(itemId)
  if (!Number.isFinite(numericId) || numericId <= 0) {
    return
  }

  // ⚠️ 载荷只构造一次：重试时复用同一个 eventId 与 eventTime，
  // 否则重试会被服务端当作新事件，产生重复数据（幂等去重失效）
  const payload: BehaviorPayload = {
    itemId: numericId,
    eventType,
    eventTime: localIsoTime(),
    eventId: generateEventId(),
  }
  if (duration != null) {
    payload.duration = Math.max(0, Math.round(duration))
  }

  send(payload, 0)
}

/** 浏览埋点（进入商品详情时记录进入时刻，离开时上报停留时长） */
export function trackView(itemId: number | string, durationMs: number): void {
  reportBehavior(itemId, BehaviorType.VIEW, durationMs)
}

/** 加购埋点 */
export function trackAddCart(itemId: number | string): void {
  reportBehavior(itemId, BehaviorType.ADD_CART)
}

/** 收藏埋点 */
export function trackFavorite(itemId: number | string): void {
  reportBehavior(itemId, BehaviorType.FAVORITE)
}

/** 购买埋点 */
export function trackPurchase(itemId: number | string): void {
  reportBehavior(itemId, BehaviorType.PURCHASE)
}

/**
 * 记录商品详情页停留时长
 *
 * 用法：进入详情页调用本函数得到「离开时的上报函数」，在组件卸载或路由离开时执行。
 *
 * @param itemId 商品标识
 * @returns 上报停留时长的回调（请勿重复调用）
 */
export function startViewTimer(itemId: number | string): () => void {
  const enterAt = Date.now()
  let reported = false
  return () => {
    // 防止 onUnmounted 与路由守卫同时触发导致上报两次
    if (reported) {
      return
    }
    reported = true
    // 停留时长不足 1 秒的浏览会被服务端判为无效行为，此处仍如实上报，由服务端统一判定
    trackView(itemId, Date.now() - enterAt)
  }
}
