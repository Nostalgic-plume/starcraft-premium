import { get } from './request'
import type { DashboardAllVO, Result } from './types'

/**
 * 加载看板全量数据。
 * 必须走 admin 的 request 实例（shared createRequest 拦截器注入 token 头）——
 * 后端 /api/dashboard/all 标注 @SaCheckLogin，裸 axios 无 token 会 401 导致看板数值全空。
 * URL 拼装后仍为 /api/dashboard/all，命中 vite / Nginx 同一代理规则。
 */
export async function fetchDashboardAll(): Promise<Result<DashboardAllVO>> {
  return get<DashboardAllVO>('/dashboard/all')
}

/** 格式化金额：单位自动切换（元/万元/亿元） */
export function formatMoney(value: number | string | null | undefined, decimals = 2): string {
  if (value == null) return '—'
  const num = Number(value)
  const abs = Math.abs(num)
  if (abs >= 1e8) return (num / 1e8).toFixed(decimals) + ' 亿'
  if (abs >= 1e4) return (num / 1e4).toFixed(decimals) + ' 万'
  return num.toFixed(decimals)
}

/** 格式化数量：千分位 */
export function formatNumber(value: number | string | null | undefined): string {
  if (value == null) return '—'
  return Number(value).toLocaleString('zh-CN')
}

/** 格式化百分比（ADS 层已乘以 100，直接保留小数位加 % 号） */
export function formatPercent(value: number | string | null | undefined, decimals = 2): string {
  if (value == null) return '—'
  return Number(value).toFixed(decimals) + '%'
}