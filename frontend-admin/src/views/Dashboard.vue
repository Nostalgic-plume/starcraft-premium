<template>
  <div class="page fade-in">
    <!-- 加载态 -->
    <div v-if="loading" class="state-wrap">
      <n-spin size="large" />
    </div>

    <!-- 错误态 -->
    <n-result v-else-if="error" status="error" title="看板加载失败" :description="error">
      <template #footer>
        <n-button type="primary" @click="loadData">重试</n-button>
      </template>
    </n-result>

    <template v-else>
      <!-- Zone 1: 蓝色总览横幅 -->
      <section class="banner">
        <div class="banner-item" v-for="b in bannerList" :key="b.label">
          <div class="banner-label">{{ b.label }}</div>
          <div class="banner-value">{{ b.value }}</div>
          <div v-if="b.delta" class="banner-delta" :class="b.delta.up ? 'up' : 'down'">
            {{ b.delta.up ? '▲' : '▼' }} {{ Math.abs(b.delta.pct).toFixed(1) }}% 较前日
          </div>
          <div v-else class="banner-delta muted">实时快照</div>
        </div>
      </section>

      <!-- Zone 2: KPI 行 -->
      <section class="kpi-row">
        <div class="kpi-card" v-for="k in kpiList" :key="k.label">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value">{{ k.value }}</div>
          <div class="kpi-delta" :class="k.deltaClass">{{ k.deltaText }}</div>
        </div>
      </section>

      <!-- Zone 3: 图表卡 -->
      <section class="chart-grid">
        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">销售额趋势</div>
            <n-radio-group v-model:value="trendDays" size="small">
              <n-radio-button :value="7">7天</n-radio-button>
              <n-radio-button :value="30">30天</n-radio-button>
            </n-radio-group>
          </div>
          <div v-if="trend.length" ref="trendRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无趋势数据</div>
        </div>

        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">订单状态日趋势</div>
          </div>
          <div v-if="orderTrend.length" ref="orderTrendRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无订单状态数据</div>
        </div>

        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">商品销量排行 TOP10</div>
          </div>
          <div v-if="productSales.length" ref="productRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无排行数据</div>
        </div>

        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">分类销售排行</div>
          </div>
          <div v-if="categorySales.length" ref="categoryRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无分类数据</div>
        </div>

        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">周趋势与环比</div>
          </div>
          <div v-if="weeklyTrend.length" ref="weeklyRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无周趋势数据</div>
        </div>

        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">GMV 与付费用户（近 10 天）</div>
          </div>
          <div v-if="gmvTrend.length" ref="gmvRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无 GMV 数据</div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { NButton, NRadioButton, NRadioGroup, NResult, NSpin } from 'naive-ui'
import { dashboardApi } from '@/api/dashboard'
import { fetchDashboardAll } from '@/api/report'
import { CHART_ACCENT, CHART_BARS, CHART_TEXT } from '@shared/js/echarts-theme'
import type {
  AdminStatsVO,
  CategorySalesVO,
  DashboardAllVO,
  GmvTrendPointVO,
  OrderTrendPointVO,
  ProductSalesVO,
  SalesTrendVO,
  WeeklyTrendVO
} from '@/api/types'

// ==================== 数据状态 ====================

const loading = ref(false)
const error = ref('')

const stats = ref<AdminStatsVO | null>(null)
const trend = ref<SalesTrendVO[]>([])
const productSales = ref<ProductSalesVO[]>([])
const userGrowth = ref<SalesTrendVO[]>([])
const all = ref<DashboardAllVO | null>(null)
const trendDays = ref<number>(7)

/** ADS 多天序列（随 dashboard/all 一起返回，不额外请求） */
const orderTrend = computed<OrderTrendPointVO[]>(() => all.value?.orderTrend || [])
const categorySales = computed<CategorySalesVO[]>(() => all.value?.categorySales || [])
const weeklyTrend = computed<WeeklyTrendVO[]>(() => all.value?.weeklyTrend || [])
const gmvTrend = computed<GmvTrendPointVO[]>(() => all.value?.gmvTrend || [])

// ==================== 图表实例 ====================

const trendRef = ref<HTMLElement | null>(null)
const productRef = ref<HTMLElement | null>(null)
const orderTrendRef = ref<HTMLElement | null>(null)
const categoryRef = ref<HTMLElement | null>(null)
const weeklyRef = ref<HTMLElement | null>(null)
const gmvRef = ref<HTMLElement | null>(null)

let trendChart: echarts.ECharts | null = null
let productChart: echarts.ECharts | null = null
let orderTrendChart: echarts.ECharts | null = null
let categoryChart: echarts.ECharts | null = null
let weeklyChart: echarts.ECharts | null = null
let gmvChart: echarts.ECharts | null = null

// ==================== 数据加载 ====================

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    // Promise.allSettled：单个接口 403/超时/失败不拖垮整屏，
    // 失败的卡片回退空态（图表显示「暂无数据」，横幅/KPI 显示 —），仅全部失败才进入全局错误态
    const results = await Promise.allSettled([
      dashboardApi.getStats(),
      dashboardApi.getSalesTrend(trendDays.value),
      dashboardApi.getProductSales(),
      dashboardApi.getUserGrowth(),
      fetchDashboardAll()
    ])
    const [s, t, p, g, allRes] = results
    stats.value = s.status === 'fulfilled' ? ((s.value?.data as AdminStatsVO | undefined) || null) : null
    trend.value = t.status === 'fulfilled' ? (((t.value?.data as SalesTrendVO[] | undefined) || []) as SalesTrendVO[]) : []
    productSales.value = p.status === 'fulfilled' ? (((p.value?.data as ProductSalesVO[] | undefined) || []) as ProductSalesVO[]) : []
    userGrowth.value = g.status === 'fulfilled' ? (((g.value?.data as SalesTrendVO[] | undefined) || []) as SalesTrendVO[]) : []
    all.value = allRes.status === 'fulfilled' ? (allRes.value.data || null) : null

    const failedCount = results.filter((r) => r.status === 'rejected').length
    if (failedCount === results.length) {
      error.value = '看板数据加载失败，请确认账号具备数据看板权限后重试'
    } else if (failedCount > 0) {
      console.warn(`Dashboard 部分数据接口加载失败（${failedCount}/${results.length}），已回退空态`, results.filter((r) => r.status === 'rejected'))
    }
  } catch (e) {
    // Promise.allSettled 本身不抛出，此处仅为兜底
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
    await nextTick()
    initCharts()
  }
}

watch(trendDays, async () => {
  if (loading.value) return
  try {
    const t = await dashboardApi.getSalesTrend(trendDays.value)
    trend.value = ((t?.data as SalesTrendVO[] | undefined) || []) as SalesTrendVO[]
    await nextTick()
    initCharts()
  } catch {
    // 趋势重载失败时保留旧图
  }
})

// ==================== 涨跌计算 ====================

interface Delta {
  pct: number
  up: boolean
}

/**
 * 销售趋势最后两日环比（amount=销售额 / cnt=订单数）。
 * 后端 sales-trend SQL 不含 cnt（恒 null → Number() 为 0），
 * 前后均为 0 / 数据缺失时返回 null，避免恒显「▲ 0.0%」。
 */
function trendDelta(key: 'amount' | 'cnt'): Delta | null {
  if (trend.value.length < 2) return null
  const vals = trend.value.map((d) => Number(d[key]) || 0)
  const cur = vals[vals.length - 1]
  const prev = vals[vals.length - 2]
  if (cur === 0 && prev === 0) return null
  if (prev === 0) return { pct: cur > 0 ? 100 : 0, up: cur >= 0 }
  return { pct: ((cur - prev) / prev) * 100, up: cur >= prev }
}

/** 看板全量 GMV 日趋势最后两日环比（orderCount，取自 dashboard/all 同源） */
function gmvTrendDelta(): Delta | null {
  const gmvTrend = all.value?.gmvTrend
  if (!gmvTrend || gmvTrend.length < 2) return null
  const vals = gmvTrend.map((p) => Number(p.orderCount) || 0)
  const cur = vals[vals.length - 1]
  const prev = vals[vals.length - 2]
  if (cur === 0 && prev === 0) return null
  if (prev === 0) return { pct: cur > 0 ? 100 : 0, up: cur >= 0 }
  return { pct: ((cur - prev) / prev) * 100, up: cur >= prev }
}

/** 用户增长趋势最后两日环比（同样加 0/0 空值守卫） */
function growthDelta(): Delta | null {
  if (userGrowth.value.length < 2) return null
  const vals = userGrowth.value.map((d) => Number(d.cnt) || 0)
  const cur = vals[vals.length - 1]
  const prev = vals[vals.length - 2]
  if (cur === 0 && prev === 0) return null
  if (prev === 0) return { pct: cur > 0 ? 100 : 0, up: cur >= 0 }
  return { pct: ((cur - prev) / prev) * 100, up: cur >= prev }
}

// ==================== 展示数据 ====================

function fmtMoney(v: number | null | undefined): string {
  if (v == null) return '—'
  const n = Number(v)
  const abs = Math.abs(n)
  if (abs >= 1e8) return '¥' + (n / 1e8).toFixed(2) + '亿'
  if (abs >= 1e4) return '¥' + (n / 1e4).toFixed(1) + '万'
  return '¥' + n.toLocaleString('zh-CN')
}

function fmtNumber(v: number | string | null | undefined): string {
  if (v == null) return '—'
  return Number(v).toLocaleString('zh-CN')
}

/** 百分比：ADS 层已乘以 100，直接保留一位小数（对齐 report.ts 的 formatPercent） */
function fmtPct(v: number | string | null | undefined): string {
  if (v == null) return '—'
  return Number(v).toFixed(1) + '%'
}

function axisMoney(v: number): string {
  const abs = Math.abs(v)
  if (abs >= 1e8) return (v / 1e8).toFixed(1) + '亿'
  if (abs >= 1e4) return (v / 1e4).toFixed(1) + '万'
  return String(v)
}

interface BannerItem {
  label: string
  value: string
  delta: Delta | null
}

const bannerList = computed<BannerItem[]>(() => {
  const gmvDaily = all.value?.gmvDaily
  const userNew = all.value?.userNewRetain
  const orderStats = all.value?.orderStats
  const tDelta = trendDelta('amount')
  // sales-trend 无 cnt，今日订单涨跌改从 dashboard/all.gmvTrend 的同源 orderCount 计算
  const oDelta = gmvTrendDelta()
  const gDelta = growthDelta()
  let refundText = '—'
  if (orderStats && Number(orderStats.totalOrders) > 0) {
    const rate = (Number(orderStats.refundOrders) / Number(orderStats.totalOrders)) * 100
    refundText = rate.toFixed(1) + '%'
  }
  return [
    { label: '今日销售额', value: fmtMoney(gmvDaily?.gmv), delta: tDelta },
    { label: '今日订单', value: fmtNumber(gmvDaily?.orderCount), delta: oDelta },
    { label: '新增用户', value: fmtNumber(userNew?.newUsers), delta: gDelta },
    { label: '退款率', value: refundText, delta: null }
  ]
})

interface KpiItem {
  label: string
  value: string
  deltaText: string
  deltaClass: 'up' | 'down' | 'flat'
}

const kpiList = computed<KpiItem[]>(() => {
  const s = stats.value
  const gmvDaily = all.value?.gmvDaily
  const userNew = all.value?.userNewRetain
  const overview = all.value?.overview
  const saleDelta = trendDelta('amount')
  return [
    {
      label: '用户总数',
      value: fmtNumber(s?.totalUsers),
      deltaText: userNew != null ? `今日 +${userNew.newUsers}` : '—',
      deltaClass: 'up'
    },
    { label: '商品总数', value: fmtNumber(s?.totalProducts), deltaText: '—', deltaClass: 'flat' },
    {
      label: '订单总数',
      value: fmtNumber(s?.totalOrders),
      deltaText: gmvDaily != null ? `今日 +${gmvDaily.orderCount}` : '—',
      deltaClass: 'up'
    },
    {
      label: '销售额',
      value: fmtMoney(s?.totalSales),
      deltaText: saleDelta ? `${saleDelta.up ? '▲' : '▼'} ${Math.abs(saleDelta.pct).toFixed(1)}%` : '—',
      deltaClass: saleDelta ? (saleDelta.up ? 'up' : 'down') : 'flat'
    },
    { label: '待支付', value: fmtNumber(s?.pendingOrders), deltaText: '—', deltaClass: 'flat' },
    { label: '客单价', value: fmtMoney(overview?.avgOrderPrice), deltaText: '近一日', deltaClass: 'flat' },
    {
      label: '次日留存',
      value: fmtPct(userNew?.day1RetainRate),
      deltaText: userNew ? `留存 ${fmtNumber(userNew.day1RetainUsers)} 人` : '—',
      deltaClass: 'flat'
    },
    {
      label: '7日留存',
      value: fmtPct(userNew?.day7RetainRate),
      deltaText: userNew ? `留存 ${fmtNumber(userNew.day7RetainUsers)} 人` : '—',
      deltaClass: 'flat'
    }
  ]
})

// ==================== ECharts 蓝系图表 ====================

function buildTrendOption(): echarts.EChartsOption {
  const dates = trend.value.map((d) => (d.date || '').slice(5))
  const amounts = trend.value.map((d) => Number(d.amount) || 0)
  // 后端 sales-trend SQL 不含 cnt（恒 null），仅在有真实订单数时才展示折线
  const hasOrder = trend.value.some((d) => d.cnt != null && Number(d.cnt) > 0)
  const orders = trend.value.map((d) => Number(d.cnt) || 0)
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(23,34,47,0.92)',
      borderColor: 'rgba(255,255,255,0.1)',
      textStyle: { color: '#fff' }
    },
    legend: {
      top: 0,
      right: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      data: hasOrder ? ['销售额', '订单数'] : ['销售额'],
      textStyle: { color: CHART_TEXT, fontSize: 12 }
    },
    grid: { left: 8, right: 8, top: 34, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#D8DCE4' } },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 11 }
    },
    yAxis: hasOrder
      ? [
          {
            type: 'value',
            name: '销售额',
            nameTextStyle: { color: CHART_TEXT, fontSize: 11 },
            axisLabel: { color: CHART_TEXT, fontSize: 11, formatter: (v: number) => axisMoney(v) },
            splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
          },
          {
            type: 'value',
            name: '订单数',
            nameTextStyle: { color: CHART_TEXT, fontSize: 11 },
            axisLabel: { color: CHART_TEXT, fontSize: 11 },
            splitLine: { show: false }
          }
        ]
      : [
          {
            type: 'value',
            name: '销售额',
            nameTextStyle: { color: CHART_TEXT, fontSize: 11 },
            axisLabel: { color: CHART_TEXT, fontSize: 11, formatter: (v: number) => axisMoney(v) },
            splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
          }
        ],
    series: hasOrder
      ? [
          {
            name: '销售额',
            type: 'bar',
            yAxisIndex: 0,
            barWidth: '38%',
            itemStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#5AA3FF' },
                { offset: 1, color: '#2D7FF9' }
              ]),
              borderRadius: [4, 4, 0, 0]
            },
            emphasis: { itemStyle: { color: '#1E63D6' } },
            data: amounts
          },
          {
            name: '订单数',
            type: 'line',
            yAxisIndex: 1,
            smooth: true,
            symbol: 'circle',
            symbolSize: 5,
            lineStyle: { width: 2.2, color: CHART_BARS[3] },
            itemStyle: { color: CHART_BARS[3] },
            data: orders
          }
        ]
      : [
          {
            name: '销售额',
            type: 'bar',
            yAxisIndex: 0,
            barWidth: '38%',
            itemStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#5AA3FF' },
                { offset: 1, color: '#2D7FF9' }
              ]),
              borderRadius: [4, 4, 0, 0]
            },
            emphasis: { itemStyle: { color: '#1E63D6' } },
            data: amounts
          }
        ]
  }
}

/**
 * 订单状态日趋势：堆叠柱（ADS ads_order_stats_daily 多天构成）。
 * 替代原「订单分布」饼图 —— 饼图只有最新一天快照，浪费了 10 天数据。
 */
function buildOrderTrendOption(): echarts.EChartsOption {
  const rows = orderTrend.value
  const defs: Array<{ name: string; color: string; get: (r: OrderTrendPointVO) => number }> = [
    { name: '已支付', color: CHART_BARS[0], get: (r) => Number(r.paidOrders) || 0 },
    { name: '已发货', color: CHART_BARS[1], get: (r) => Number(r.shippedOrders) || 0 },
    { name: '已完成', color: CHART_BARS[2], get: (r) => Number(r.completedOrders) || 0 },
    { name: '已取消', color: CHART_BARS[3], get: (r) => Number(r.cancelledOrders) || 0 }
  ]
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(23,34,47,0.92)',
      borderColor: 'rgba(255,255,255,0.1)',
      textStyle: { color: '#fff' }
    },
    legend: { top: 0, right: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: CHART_TEXT, fontSize: 11 } },
    grid: { left: 4, right: 8, top: 30, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((r) => String(r.dt || '').slice(5)),
      axisLine: { lineStyle: { color: '#D8DCE4' } },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: CHART_TEXT, fontSize: 10 },
      splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
    },
    series: defs.map((d) => ({
      name: d.name,
      type: 'bar',
      stack: 'status',
      barWidth: '46%',
      itemStyle: { color: d.color },
      data: rows.map(d.get)
    }))
  }
}

/** 分类销售排行：横向条形（ADS ads_category_sales，取金额 TOP8 保证标签可读） */
function buildCategoryOption(): echarts.EChartsOption {
  const top = [...categorySales.value]
    .sort((a, b) => (Number(a.saleAmount) || 0) - (Number(b.saleAmount) || 0))
    .slice(-8)
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(23,34,47,0.92)',
      borderColor: 'rgba(255,255,255,0.1)',
      textStyle: { color: '#fff' }
    },
    grid: { left: 4, right: 56, top: 8, bottom: 0, containLabel: true },
    xAxis: {
      type: 'value',
      axisLabel: { color: CHART_TEXT, fontSize: 10, formatter: (v: number) => axisMoney(v) },
      splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
    },
    yAxis: {
      type: 'category',
      data: top.map((d) => d.categoryName),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 11 }
    },
    series: [
      {
        type: 'bar',
        barWidth: 12,
        data: top.map((d) => Number(d.saleAmount) || 0),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: '#5AA3FF' },
            { offset: 1, color: '#2D7FF9' }
          ]),
          borderRadius: [0, 6, 6, 0]
        },
        label: {
          show: true,
          position: 'right',
          color: CHART_TEXT,
          fontSize: 10,
          formatter: (p) => axisMoney(Number(p.value))
        }
      }
    ]
  }
}

function buildProductOption(): echarts.EChartsOption {
  const top = [...productSales.value].slice(0, 10).reverse()
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(23,34,47,0.92)',
      borderColor: 'rgba(255,255,255,0.1)',
      textStyle: { color: '#fff' }
    },
    grid: { left: 8, right: 46, top: 8, bottom: 0, containLabel: true },
    xAxis: {
      type: 'value',
      splitNumber: 3,
      axisLabel: { color: CHART_TEXT, fontSize: 10, formatter: (v: number) => axisMoney(v) },
      splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
    },
    yAxis: {
      type: 'category',
      data: top.map((d) => d.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 12 }
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        data: top.map((d) => Number(d.totalSales) || 0),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: '#2D7FF9' },
            { offset: 1, color: '#8CBFFF' }
          ]),
          borderRadius: [0, 6, 6, 0]
        },
        label: {
          show: true,
          position: 'right',
          color: CHART_TEXT,
          fontSize: 11,
          formatter: (p) => axisMoney(Number(p.value))
        }
      }
    ]
  }
}

/** 周趋势与环比：GMV 柱 + 环比折线（右轴 %，ADS ads_trend_weekly） */
function buildWeeklyOption(): echarts.EChartsOption {
  const rows = weeklyTrend.value
  const labels = rows.map((r) => `${String(r.weekStart || '').slice(5)}~${String(r.weekEnd || '').slice(5)}`)
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(23,34,47,0.92)',
      borderColor: 'rgba(255,255,255,0.1)',
      textStyle: { color: '#fff' }
    },
    legend: { top: 0, right: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, data: ['GMV', '环比'], textStyle: { color: CHART_TEXT, fontSize: 11 } },
    grid: { left: 4, right: 8, top: 30, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: '#D8DCE4' } },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 10 }
    },
    yAxis: [
      {
        type: 'value',
        axisLabel: { color: CHART_TEXT, fontSize: 10, formatter: (v: number) => axisMoney(v) },
        splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
      },
      {
        type: 'value',
        axisLabel: { color: CHART_TEXT, fontSize: 10, formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: 'GMV',
        type: 'bar',
        barWidth: '34%',
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#5AA3FF' },
            { offset: 1, color: '#2D7FF9' }
          ]),
          borderRadius: [4, 4, 0, 0]
        },
        data: rows.map((r) => Number(r.gmv) || 0)
      },
      {
        name: '环比',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.2, color: CHART_ACCENT },
        itemStyle: { color: CHART_ACCENT },
        data: rows.map((r) => Number(r.gmvMom) || 0)
      }
    ]
  }
}

/** GMV 与付费用户：柱 + 右轴折线（ADS ads_gmv_daily 多天） */
function buildGmvOption(): echarts.EChartsOption {
  const rows = gmvTrend.value
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(23,34,47,0.92)',
      borderColor: 'rgba(255,255,255,0.1)',
      textStyle: { color: '#fff' }
    },
    legend: { top: 0, right: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, data: ['GMV', '付费用户数'], textStyle: { color: CHART_TEXT, fontSize: 11 } },
    grid: { left: 4, right: 8, top: 30, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((r) => String(r.dt || '').slice(5)),
      axisLine: { lineStyle: { color: '#D8DCE4' } },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 10 }
    },
    yAxis: [
      {
        type: 'value',
        axisLabel: { color: CHART_TEXT, fontSize: 10, formatter: (v: number) => axisMoney(v) },
        splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
      },
      {
        type: 'value',
        axisLabel: { color: CHART_TEXT, fontSize: 10 },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: 'GMV',
        type: 'bar',
        barWidth: '40%',
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#5AA3FF' },
            { offset: 1, color: '#2D7FF9' }
          ]),
          borderRadius: [4, 4, 0, 0]
        },
        data: rows.map((r) => Number(r.gmv) || 0)
      },
      {
        name: '付费用户数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { width: 2.2, color: CHART_BARS[3] },
        itemStyle: { color: CHART_BARS[3] },
        data: rows.map((r) => Number(r.paidUserCount) || 0)
      }
    ]
  }
}

// ==================== 图表生命周期 ====================

function initChart(el: HTMLElement, option: echarts.EChartsOption): echarts.ECharts {
  const chart = echarts.init(el)
  chart.setOption(option)
  return chart
}

function disposeCharts() {
  ;[trendChart, orderTrendChart, productChart, categoryChart, weeklyChart, gmvChart].forEach((c) => {
    if (c) {
      try {
        c.dispose()
      } catch {
        /* 容器已被 v-if 移除时忽略 */
      }
    }
  })
  trendChart = orderTrendChart = productChart = categoryChart = weeklyChart = gmvChart = null
}

function initCharts() {
  disposeCharts()
  if (trendRef.value) trendChart = initChart(trendRef.value, buildTrendOption())
  if (orderTrendRef.value) orderTrendChart = initChart(orderTrendRef.value, buildOrderTrendOption())
  if (productRef.value) productChart = initChart(productRef.value, buildProductOption())
  if (categoryRef.value) categoryChart = initChart(categoryRef.value, buildCategoryOption())
  if (weeklyRef.value) weeklyChart = initChart(weeklyRef.value, buildWeeklyOption())
  if (gmvRef.value) gmvChart = initChart(gmvRef.value, buildGmvOption())
}

function handleResize() {
  ;[trendChart, orderTrendChart, productChart, categoryChart, weeklyChart, gmvChart].forEach((c) => c?.resize())
}

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  disposeCharts()
})
</script>

<style scoped>
.page {
  padding: 20px;
}

.state-wrap {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 320px;
}

/* ====== 蓝色总览横幅 ====== */
.banner {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  background: linear-gradient(135deg, #5aa3ff 0%, #2d7ff9 55%, #1e63d6 100%);
  border-radius: var(--ds-r-lg, 16px);
  padding: 14px 18px;
  margin-bottom: 12px;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
}
.banner-item {
  color: #fff;
  padding: 2px 6px;
}
.banner-label {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.78);
  letter-spacing: 0.3px;
}
.banner-value {
  font-family: var(--ds-mono, Consolas, monospace);
  font-size: 21px;
  font-weight: 700;
  line-height: 1.2;
  margin: 2px 0;
}
.banner-delta {
  font-size: 12px;
  font-family: var(--ds-mono, Consolas, monospace);
}
.banner-delta.up {
  color: #bff5e3;
}
.banner-delta.down {
  color: #ffd3d7;
}
.banner-delta.muted {
  color: rgba(255, 255, 255, 0.6);
}

/* ====== KPI 行 ====== */
.kpi-row {
  display: grid;
  /* 一屏放全：8 张 KPI 卡一行铺满 */
  grid-template-columns: repeat(8, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}
.kpi-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 10px 12px;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}
.kpi-card:hover {
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
  transform: translateY(-1px);
}
.kpi-label {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.kpi-value {
  font-family: var(--ds-mono, Consolas, monospace);
  font-size: 19px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 4px 0 2px;
  line-height: 1.2;
}
.kpi-delta {
  font-size: 12px;
  font-family: var(--ds-mono, Consolas, monospace);
}
.kpi-delta.up {
  color: var(--ds-green, #12a77d);
}
.kpi-delta.down {
  color: var(--ds-red, #e0404e);
}
.kpi-delta.flat {
  color: var(--ds-text-3, #98a1af);
}

/* ====== 图表卡 ====== */
.chart-grid {
  display: grid;
  /* 2 行 × 3 列：6 张图一屏放全 */
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.chart-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 12px 12px 8px;
}
.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.chart-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
}
.chart-box {
  width: 100%;
  height: 240px;
}
.chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ds-text-3, #98a1af);
  font-size: 13px;
  background: var(--ds-bg-2, #eef0f4);
  border-radius: 10px;
}

/* 窄屏回退：3 列 → 2 列 → 1 列；KPI 8 列 → 4 列 → 2 列 */
@media (max-width: 1400px) {
  .kpi-row {
    grid-template-columns: repeat(4, 1fr);
  }
  .chart-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 900px) {
  .banner {
    grid-template-columns: repeat(2, 1fr);
  }
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
