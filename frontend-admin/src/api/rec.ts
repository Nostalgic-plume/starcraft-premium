import { del, get, post, put } from './request'
import type {
  RecConfigItem,
  RecFilterRule,
  RecFixedSlot,
  RecItemRebuildStatus,
  RecMetricDefinition,
  RecMetricRecord,
  RecProfileVersion,
  Result
} from './types'

/**
 * 推荐子系统管理 API
 *
 * <p>对照后端四个控制器，全部由<b>推荐服务进程（8082）</b>提供（均为 `@Profile("rec")`，
 * 商城后端没有这些 Bean），经 vite 的 '/api/rec' 代理直达：
 * <ul>
 *     <li>RecConfigController —— 策略配置（E-03）</li>
 *     <li>RecSlotRuleController —— 固定坑位与过滤规则（E-04）</li>
 *     <li>RecProfileController —— 画像版本回滚（E-08）</li>
 *     <li>RecMetricController —— 运行指标（M-10）</li>
 * </ul>
 *
 * <p>URL 不写 '/api' 前缀，由 createRequest 的 baseURL 自动拼接。
 * 修改类接口均校验管理后台登录态，未登录返回错误码 4003。
 */
export const recAdminApi = {
  // ==================== 策略配置 ====================

  /**
   * 查询策略配置项
   *
   * @param groupName 配置分组，可选；为空返回全部
   */
  listConfigs(groupName?: string): Promise<Result<RecConfigItem[]>> {
    return get<RecConfigItem[]>('/rec/config', { params: { groupName } })
  },

  /**
   * 批量修改配置
   *
   * <p>校验不通过时后端拒绝写入并保留旧值（错误码 4001）。
   *
   * @param items 待修改的配置项（仅需键、值、值类型）
   * @return 生效后的配置版本号，各模块 30 秒内热更新
   */
  updateConfigs(
    items: Array<{ configKey: string; configValue: string; valueType?: string }>
  ): Promise<Result<string>> {
    return put<string>('/rec/config', { items })
  },

  // ==================== 固定坑位 ====================

  /** 查询全部固定坑位 */
  listSlots(): Promise<Result<RecFixedSlot[]>> {
    return get<RecFixedSlot[]>('/rec/slots')
  },

  /** 新增坑位（位置冲突时后端拒绝，错误码 4002） */
  createSlot(slot: RecFixedSlot): Promise<Result<RecFixedSlot>> {
    return post<RecFixedSlot>('/rec/slots', slot)
  },

  /** 修改坑位（须含主键） */
  updateSlot(slot: RecFixedSlot): Promise<Result<RecFixedSlot>> {
    return put<RecFixedSlot>('/rec/slots', slot)
  },

  /** 删除坑位 */
  deleteSlot(id: string): Promise<Result<void>> {
    return del<void>('/rec/slots/' + id)
  },

  // ==================== 过滤规则 ====================

  /** 查询全部过滤规则 */
  listRules(): Promise<Result<RecFilterRule[]>> {
    return get<RecFilterRule[]>('/rec/rules')
  },

  /** 新增过滤规则 */
  createRule(rule: RecFilterRule): Promise<Result<RecFilterRule>> {
    return post<RecFilterRule>('/rec/rules', rule)
  },

  /** 修改过滤规则（须含主键） */
  updateRule(rule: RecFilterRule): Promise<Result<RecFilterRule>> {
    return put<RecFilterRule>('/rec/rules', rule)
  },

  /** 删除过滤规则 */
  deleteRule(id: string): Promise<Result<void>> {
    return del<void>('/rec/rules/' + id)
  },

  // ==================== 画像版本 ====================

  /**
   * 查询指定用户的画像版本列表
   *
   * <p>回滚的前置接口：需先看到保留范围内的版本与偏好内容，才能选定目标版本。
   */
  listProfileVersions(userId: string): Promise<Result<RecProfileVersion[]>> {
    return get<RecProfileVersion[]>(`/rec/profiles/${userId}/versions`)
  },

  /**
   * 回滚画像版本
   *
   * <p>目标版本不在保留范围内时后端拒绝并返回错误码 4004，当前有效版本保持不变。
   */
  rollbackProfile(
    userId: string,
    targetVersion: number,
    remark?: string
  ): Promise<Result<{ userId: string; currentVersion: number }>> {
    return post<{ userId: string; currentVersion: number }>(`/rec/profiles/${userId}/rollback`, {
      targetVersion,
      remark
    })
  },

  // ==================== 运行指标 ====================

  /**
   * 查询指标清单
   *
   * <p>含中文名、单位、预警阈值与好坏方向。阈值定义在后端，看板据此标色，
   * 避免两端各写一份阈值而漂移。
   */
  metricCatalog(): Promise<Result<RecMetricDefinition[]>> {
    return get<RecMetricDefinition[]>('/rec/metrics/catalog')
  },

  /**
   * 查询指标明细
   *
   * @param params hours 缺省 24、上限 168（越界回落默认值）；limit 缺省 100、上限 500
   */
  listMetrics(params?: {
    metricName?: string
    hours?: number
    limit?: number
  }): Promise<Result<RecMetricRecord[]>> {
    return get<RecMetricRecord[]>('/rec/metrics', { params })
  },

  // ==================== 商品索引 ====================

  /**
   * 查询商品索引重建状态
   *
   * 以 `rebuilding` 变为 false 作为「本轮重建结束」的信号，
   * 再按 `lastRebuildSuccess` 判断成败。
   */
  itemRebuildStatus(): Promise<Result<RecItemRebuildStatus>> {
    return get<RecItemRebuildStatus>('/rec/items/rebuild-status')
  },

  /**
   * 触发商品索引全量重建（异步执行，立即返回）
   *
   * 已有重建在执行中时后端拒绝并返回错误码 4005。
   */
  triggerItemRebuild(): Promise<Result<void>> {
    return post<void>('/rec/items/rebuild')
  }
}
