<template>
  <div class="page fade-in">
    <!-- 加载态 -->
    <div v-if="loading" class="state-wrap">
      <n-spin size="large" />
    </div>

    <!-- 错误态（全部数据源失败） -->
    <n-result v-else-if="error" status="error" title="看板加载失败" :description="error">
      <template #footer>
        <n-button type="primary" @click="loadData">重试</n-button>
      </template>
    </n-result>

    <template v-else>
      <!-- Zone 1: 蓝色总览横幅（渐变蓝底 + 大字） -->
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

      <!-- Zone 2: KPI 行（mono 大数字 + delta） -->
      <section class="kpi-row">
        <div class="kpi-card" v-for="k in kpiList" :key="k.label">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value">{{ k.value }}</div>
          <div class="kpi-delta" :class="k.deltaClass">{{ k.deltaText }}</div>
        </div>
      </section>

      <!-- Zone 3: 图表卡（ECharts 蓝系） -->
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
            <div class="chart-title">订单分布</div>
          </div>
          <div v-if="distribution.length" ref="distRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无分布数据</div>
        </div>

        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">本店商品销量 TOP5</div>
          </div>
          <div v-if="productRank.length" ref="productRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无销量数据</div>
        </div>

        <div class="chart-card">
          <div class="chart-header">
            <div class="chart-title">每日订单数</div>
          </div>
          <div v-if="dailyOrders.length" ref="dailyRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无订单数据</div>
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
import { CHART_BARS, CHART_LINE, CHART_TEXT } from '@shared/js/echarts-theme'
import type {
  DailyOrdersPoint,
  MerchantStatsVO,
  OrderDistItem,
  OrderVO,
  ProductRankItem,
  ProductVO
} from '@/api/types'

// ==================== 数据状态 ====================

const loading = ref(false)
const error = ref('')
const trendDays = ref<number>(7)

/** 各数据源加载成功标记（单个失败回退空态，全部失败进全局错误态） */
const statsLoaded = ref(false)
const ordersLoaded = ref(false)
const productsLoaded = ref(false)

const stats = ref<MerchantStatsVO | null>(null)
const orders = ref<OrderVO[]>([])
const products = ref<ProductVO[]>([])

/** 销售额趋势点（订单按日期分组，组件内组装） */
interface TrendPoint {
  date: string
  amount: number
  count: number
}

const trend = ref<TrendPoint[]>([])
const distribution = ref<OrderDistItem[]>([])
const productRank = ref<ProductRankItem[]>([])
const dailyOrders = ref<DailyOrdersPoint[]>([])

// ==================== 图表实例 ====================

const trendRef = ref<HTMLElement | null>(null)
const distRef = ref<HTMLElement | null>(null)
const productRef = ref<HTMLElement | null>(null)
const dailyRef = ref<HTMLElement | null>(null)

let trendChart: echarts.ECharts | null = null
let distChart: echarts.ECharts | null = null
let productChart: echarts.ECharts | null = null
let dailyChart: echarts.ECharts | null = null

// ==================== 数据加载（Promise.allSettled 防御） ====================

function buildTrend() {
  const days = trendDays.value
  const dates: string[] = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    dates.push(d.toISOString().slice(0, 10))
  }
  const amountMap: Record<string, number> = {}
  const countMap: Record<string, number> = {}
  // 销售额趋势只统计已支付且未取消/退款的订单（1=已支付 2=已发货 3=已收货 4=已完成）
  orders.value
    .filter((o) => Number(o.status) >= 1 && Number(o.status) < 5)
    .forEach((o) => {
      const date = (o.createTime || '').slice(0, 10)
      if (!date) return
      amountMap[date] = (amountMap[date] || 0) + Number(o.totalAmount || 0)
      countMap[date] = (countMap[date] || 0) + 1
    })
  trend.value = dates.map((d) => ({ date: d, amount: amountMap[d] || 0, count: countMap[d] || 0 }))
}

function buildOrderDist() {
  const map: Record<string, number> = {}
  orders.value.forEach((o) => {
    const key = String(o.status)
    map[key] = (map[key] || 0) + 1
  })
  distribution.value = Object.entries(map).map(([k, v]) => ({ status: Number(k), cnt: v }))
}

/** 每日订单数（最近 7 天，按 createTime 日期分组） */
function buildDailyOrders() {
  const dates: string[] = []
  for (let i = 6; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    dates.push(d.toISOString().slice(0, 10))
  }
  const map: Record<string, number> = {}
  orders.value.forEach((o) => {
    const date = (o.createTime || '').slice(0, 10)
    if (date) map[date] = (map[date] || 0) + 1
  })
  dailyOrders.value = dates.map((d) => ({ date: d.slice(5), count: map[d] || 0 }))
}

/** 本店商品销量 TOP5（商家端商品接口无 totalSales，统一用 soldCount） */
function buildProductRank() {
  productRank.value = products.value
    .filter((p) => Number(p.soldCount) > 0)
    .sort((a, b) => Number(b.soldCount) - Number(a.soldCount))
    .slice(0, 5)
    .map((p) => ({ name: p.name, soldCount: Number(p.soldCount) || 0 }))
}

async function loadData() {
  loading.value = true
  error.value = ''
  statsLoaded.value = false
  ordersLoaded.value = false
  productsLoaded.value = false
  try {
    // Promise.allSettled：单个接口 401/超时/失败不拖垮整屏，
    // 失败的卡片回退空态（图表「暂无数据」，横幅/KPI 显示 —），仅全部失败才进入全局错误态
    const results = await Promise.allSettled([
      dashboardApi.getStats(),
      dashboardApi.getOrders(),
      dashboardApi.getProducts()
    ])
    const [s, o, p] = results

    if (s.status === 'fulfilled') {
      stats.value = (s.value?.data as MerchantStatsVO | undefined) || null
      statsLoaded.value = true
    }
    if (o.status === 'fulfilled') {
      orders.value = ((o.value?.data as { list?: OrderVO[] } | undefined)?.list) || []
      ordersLoaded.value = true
    }
    if (p.status === 'fulfilled') {
      products.value = ((p.value?.data as { list?: ProductVO[] } | undefined)?.list) || []
      productsLoaded.value = true
    }

    // 派生图表数据
    buildTrend()
    buildOrderDist()
    buildDailyOrders()
    buildProductRank()

    const failedCount = results.filter((r) => r.status === 'rejected').length
    if (failedCount === results.length) {
      error.value = '看板数据加载失败，请确认登录态后重试'
    } else if (failedCount > 0) {
      console.warn(
        `Dashboard 部分数据接口加载失败（${failedCount}/${results.length}），已回退空态`,
        results.filter((r) => r.status === 'rejected')
      )
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
  buildTrend()
  await nextTick()
  initCharts()
})

// ==================== 涨跌计算 ====================

interface Delta {
  pct: number
  up: boolean
}

/** 销售趋势最后两日环比（amount=销售额 / count=订单数）。前后均为 0 / 数据缺失返回 null。 */
function trendDelta(key: 'amount' | 'count'): Delta | null {
  if (trend.value.length < 2) return null
  const vals = trend.value.map((d) => Number(d[key]) || 0)
  const cur = vals[vals.length - 1]
  const prev = vals[vals.length - 2]
  if (cur === 0 && prev === 0) return null
  if (prev === 0) return { pct: cur > 0 ? 100 : 0, up: cur >= 0 }
  return { pct: ((cur - prev) / prev) * 100, up: cur >= prev }
}

// ==================== 展示数据 ====================

function fmtMoney(v: number | string | null | undefined): string {
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

function axisMoney(v: number): string {
  const abs = Math.abs(v)
  if (abs >= 1e8) return (v / 1e8).toFixed(1) + '亿'
  if (abs >= 1e4) return (v / 1e4).toFixed(1) + '万'
  return String(v)
}

/** 趋势末点（今日） */
const lastTrend = computed(() => (trend.value.length ? trend.value[trend.value.length - 1] : null))
const todaySales = computed(() => lastTrend.value?.amount || 0)
const todayOrders = computed(() => lastTrend.value?.count || 0)
const totalProducts = computed(() => Number(stats.value?.totalProducts) || 0)
const pendingOrders = computed(() => Number(stats.value?.pendingOrders) || 0)
const onShelf = computed(() => products.value.filter((p) => Number(p.status) === 1).length)

interface BannerItem {
  label: string
  value: string
  delta: Delta | null
}

const bannerList = computed<BannerItem[]>(() => [
  {
    label: '今日销售额',
    value: ordersLoaded.value ? fmtMoney(todaySales.value) : '—',
    delta: trendDelta('amount')
  },
  {
    label: '今日订单',
    value: ordersLoaded.value ? fmtNumber(todayOrders.value) : '—',
    delta: trendDelta('count')
  },
  {
    label: '本店商品数',
    value: statsLoaded.value ? fmtNumber(totalProducts.value) : '—',
    delta: null
  },
  {
    label: '待发货',
    value: statsLoaded.value ? fmtNumber(pendingOrders.value) : '—',
    delta: null
  }
])

interface KpiItem {
  label: string
  value: string
  deltaText: string
  deltaClass: 'up' | 'down' | 'flat'
}

const kpiList = computed<KpiItem[]>(() => {
  const d = trendDelta('amount')
  return [
    {
      label: '销售总额',
      value: statsLoaded.value ? fmtMoney(stats.value?.totalSales) : '—',
      deltaText: d ? `${d.up ? '▲' : '▼'} ${Math.abs(d.pct).toFixed(1)}%` : '—',
      deltaClass: d ? (d.up ? 'up' : 'down') : 'flat'
    },
    {
      label: '商品总数',
      value: statsLoaded.value ? fmtNumber(stats.value?.totalProducts) : '—',
      deltaText: '—',
      deltaClass: 'flat'
    },
    {
      label: '订单总数',
      value: statsLoaded.value ? fmtNumber(stats.value?.totalOrders) : '—',
      deltaText: todayOrders.value > 0 ? `今日 +${todayOrders.value}` : '—',
      deltaClass: todayOrders.value > 0 ? 'up' : 'flat'
    },
    {
      label: '待发货',
      value: statsLoaded.value ? fmtNumber(stats.value?.pendingOrders) : '—',
      deltaText: '—',
      deltaClass: 'flat'
    },
    {
      label: '上架中',
      value: productsLoaded.value ? fmtNumber(onShelf.value) : '—',
      deltaText: productsLoaded.value ? `共 ${products.value.length} 件` : '—',
      deltaClass: 'flat'
    }
  ]
})

// ==================== ECharts 蓝系图表 ====================

function buildTrendOption(): echarts.EChartsOption {
  const dates = trend.value.map((d) => d.date.slice(5))
  const amounts = trend.value.map((d) => d.amount)
  const counts = trend.value.map((d) => d.count)
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
      data: ['销售额', '订单数'],
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
    yAxis: [
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
    ],
    series: [
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
        lineStyle: { width: 2.2, color: CHART_LINE },
        itemStyle: { color: CHART_LINE },
        data: counts
      }
    ]
  }
}

function buildDistOption(): echarts.EChartsOption {
  const statusNameMap: Record<number, string> = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已收货', 4: '已完成', 5: '已取消', 6: '已退款' }
  const palette = [...CHART_BARS, '#1E63D6', '#DCE8FB', '#98A1AF']
  const data = distribution.value.map((d, i) => ({
    name: statusNameMap[Number(d.status)] || '未知',
    value: Number(d.cnt) || 0,
    itemStyle: { color: palette[i % palette.length] }
  }))
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 单 ({d}%)' },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: CHART_TEXT, fontSize: 11 }
    },
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '43%'],
        itemStyle: { borderRadius: 5, borderColor: '#fff', borderWidth: 2 },
        label: { color: CHART_TEXT, fontSize: 11, formatter: '{b}' },
        labelLine: { lineStyle: { color: '#D8DCE4' } },
        emphasis: { label: { fontSize: 13, fontWeight: 700 } },
        data
      }
    ]
  }
}

function buildProductOption(): echarts.EChartsOption {
  const top = [...productRank.value].reverse()
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
      axisLabel: { color: CHART_TEXT, fontSize: 11 },
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
        data: top.map((d) => Number(d.soldCount) || 0),
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
          formatter: (p) => String(Number(p.value)) + '件'
        }
      }
    ]
  }
}

function buildDailyOption(): echarts.EChartsOption {
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(23,34,47,0.92)',
      borderColor: 'rgba(255,255,255,0.1)',
      textStyle: { color: '#fff' }
    },
    grid: { left: 8, right: 8, top: 20, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: dailyOrders.value.map((d) => d.date),
      axisLine: { lineStyle: { color: '#D8DCE4' } },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: CHART_TEXT, fontSize: 11 },
      splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
    },
    series: [
      {
        type: 'bar',
        barWidth: '42%',
        data: dailyOrders.value.map((d) => d.count),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#8CBFFF' },
            { offset: 1, color: '#2D7FF9' }
          ]),
          borderRadius: [4, 4, 0, 0]
        },
        emphasis: { itemStyle: { color: '#1E63D6' } }
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
  ;[trendChart, distChart, productChart, dailyChart].forEach((c) => {
    if (c) {
      try {
        c.dispose()
      } catch {
        /* 容器已被 v-if 移除时忽略 */
      }
    }
  })
  trendChart = distChart = productChart = dailyChart = null
}

function initCharts() {
  disposeCharts()
  if (trendRef.value) trendChart = initChart(trendRef.value, buildTrendOption())
  if (distRef.value) distChart = initChart(distRef.value, buildDistOption())
  if (productRef.value) productChart = initChart(productRef.value, buildProductOption())
  if (dailyRef.value) dailyChart = initChart(dailyRef.value, buildDailyOption())
}

function handleResize() {
  ;[trendChart, distChart, productChart, dailyChart].forEach((c) => c?.resize())
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

.fade-in {
  animation: fadeIn 0.3s ease;
}
@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
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
  padding: 22px 24px;
  margin-bottom: 16px;
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
  font-size: 26px;
  font-weight: 700;
  line-height: 1.3;
  margin: 4px 0 3px;
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
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.kpi-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px 18px;
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
  font-size: 24px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 6px 0 3px;
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
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.chart-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px 16px 10px;
}
.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.chart-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
}
.chart-box {
  width: 100%;
  height: 300px;
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

@media (max-width: 1100px) {
  .banner {
    grid-template-columns: repeat(2, 1fr);
  }
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
