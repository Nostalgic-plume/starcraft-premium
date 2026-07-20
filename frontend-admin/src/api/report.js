import axios from 'axios'

const api = axios.create({
  baseURL: '/api/dashboard',
  timeout: 10000
})

/** 加载看板全量数据 */
export async function fetchDashboardAll() {
  const resp = await api.get('/all')
  return resp.data
}

/**
 * 格式化金额：单位自动切换（元/万元/亿元）
 */
export function formatMoney(value, decimals = 2) {
  if (value == null) return '—'
  const num = Number(value)
  const abs = Math.abs(num)
  if (abs >= 1e8) return (num / 1e8).toFixed(decimals) + ' 亿'
  if (abs >= 1e4) return (num / 1e4).toFixed(decimals) + ' 万'
  return num.toFixed(decimals)
}

/**
 * 格式化数量：千分位
 */
export function formatNumber(value) {
  if (value == null) return '—'
  return Number(value).toLocaleString('zh-CN')
}

/**
 * 格式化百分比（ADS 层已乘以 100，直接保留小数位加 % 号）
 */
export function formatPercent(value, decimals = 2) {
  if (value == null) return '—'
  return Number(value).toFixed(decimals) + '%'
}
