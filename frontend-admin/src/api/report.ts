import axios from 'axios'
import type { DashboardAllVO, Result } from './types'

const api = axios.create({
  baseURL: '/api/dashboard',
  timeout: 10000
})

/** 加载看板全量数据 */
export async function fetchDashboardAll(): Promise<Result<DashboardAllVO>> {
  const resp = await api.get<Result<DashboardAllVO>>('/all')
  return resp.data
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