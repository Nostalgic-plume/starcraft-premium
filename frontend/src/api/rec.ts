import { get } from './request'
import type { Result } from './types'

/**
 * 推荐子系统接口
 *
 * 首页榜单由商城后端代理转发至推荐服务；推荐服务不可达时后端自动返回原生热销榜，
 * 前端无需感知降级——两种情况的数据结构完全一致，仅 degraded 字段不同。
 */

/** 榜单查询参数 */
export interface RankQuery {
  /**
   * 快照标识
   *
   * ⚠️ **仅翻页时回传**：首屏加载与下拉刷新一律不传，由服务端按当前实时特征重新计算。
   * 若首屏也复用上一次的快照，「行为产生后 5 秒内影响推荐」将在快照有效期（默认 300 秒）内失效。
   */
  snapshotId?: string
  /** 页码，从 1 开始 */
  pageNum?: number
  /** 每页条数，1~50 */
  pageSize?: number
}

/** 榜单商品项 */
export interface RankItem {
  /**
   * 商品标识
   *
   * ⚠️ 类型不稳定，使用时一律先 {@code String(itemId)} 归一：
   * 推荐服务（8082）未配置 Long→String 序列化，返回数字；商城侧的兜底榜单
   * 与全站其他接口一样配了 ToStringSerializer，返回字符串。降级与否会导致类型漂移。
   */
  itemId: string | number
  itemName: string
  mainImage: string
  price: number
  /** 召回来源：CATEGORY_PREF / HOT / FALLBACK / FIXED_SLOT / NATIVE */
  recallSource: string
  /** 归一化总分，仅用于内部排查，页面不展示 */
  score: number
}

/** 榜单结果 */
export interface RankResult {
  /** 榜单快照标识，翻页时原值回传 */
  snapshotId: string | null
  /** 降级状态：正常 / 游客 / 冷启动 / 特征缺失 / 召回降级 / 兜底名单 / 上游兜底 */
  degraded: string
  pageNum: number
  pageSize: number
  /** 快照内商品总数 */
  total: number
  hasMore: boolean
  items: RankItem[]
}

/**
 * 查询首页榜单
 *
 * @param query 查询参数；首屏不传 snapshotId
 */
export function fetchHomeRank(query: RankQuery = {}): Promise<Result<RankResult>> {
  return get<RankResult>('/home/rank', { params: query })
}
