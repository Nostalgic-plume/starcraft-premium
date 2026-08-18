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
            <div class="chart-title">订单分布</div>
          </div>
          <div v-if="distribution.length" ref="distRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无分布数据</div>
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
            <div class="chart-title">用户增长（近30天）</div>
          </div>
          <div v-if="userGrowth.length" ref="growthRef" class="chart-box"></div>
          <div v-else class="chart-box chart-empty">暂无增长数据</div>
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
import { CHART_BARS, CHART_LINE, CHART_TEXT } from '@shared/js/echarts-theme'
import type { AdminStatsVO, DashboardAllVO, OrderDistItem, ProductSalesVO, SalesTrendVO } from '@/api/types'

// ==================== 数据状态 ====================

const loading = ref(false)
const error = ref('')

const stats = ref<AdminStatsVO | null>(null)
const trend = ref<SalesTrendVO[]>([])
const productSales = ref<ProductSalesVO[]>([])
const userGrowth = ref<SalesTrendVO[]>([])
const distribution = ref<OrderDistItem[]>([])
const all = ref<DashboardAllVO | null>(null)
const trendDays = ref<number>(7)

// ==================== 图表实例 ====================

const trendRef = ref<HTMLElement | null>(null)
const distRef = ref<HTMLElement | null>(null)
const productRef = ref<HTMLElement | null>(null)
const growthRef = ref<HTMLElement | null>(null)

let trendChart: echarts.ECharts | null = null
let distChart: echarts.ECharts | null = null
let productChart: echarts.ECharts | null = null
let growthChart: echarts.ECharts | null = null

// ==================== 数据加载 ====================

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [s, t, p, g, dist, allRes] = await Promise.all([
      dashboardApi.getStats(),
      dashboardApi.getSalesTrend(trendDays.value),
      dashboardApi.getProductSales(),
      dashboardApi.getUserGrowth(),
      dashboardApi.getOrderDistribution(),
      fetchDashboardAll()
    ])
    stats.value = (s?.data as AdminStatsVO | undefined) || null
    trend.value = ((t?.data as SalesTrendVO[] | undefined) || []) as SalesTrendVO[]
    productSales.value = ((p?.data as ProductSalesVO[] | undefined) || []) as ProductSalesVO[]
    userGrowth.value = ((g?.data as SalesTrendVO[] | undefined) || []) as SalesTrendVO[]
    distribution.value = ((dist?.data as OrderDistItem[] | undefined) || []) as OrderDistItem[]
    all.value = allRes.data || null
  } catch (e) {
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

/** 销售趋势最后两日环比（amount=销售额 / cnt=订单数） */
function trendDelta(key: 'amount' | 'cnt'): Delta | null {
  if (trend.value.length < 2) return null
  const vals = trend.value.map((d) => Number(d[key]) || 0)
  const cur = vals[vals.length - 1]
  const prev = vals[vals.length - 2]
  if (prev === 0) return { pct: cur > 0 ? 100 : 0, up: cur >= 0 }
  return { pct: ((cur - prev) / prev) * 100, up: cur >= prev }
}

/** 用户增长趋势最后两日环比 */
function growthDelta(): Delta | null {
  if (userGrowth.value.length < 2) return null
  const vals = userGrowth.value.map((d) => Number(d.cnt) || 0)
  const cur = vals[vals.length - 1]
  const prev = vals[vals.length - 2]
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
  const oDelta = trendDelta('cnt')
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
    { label: '待支付', value: fmtNumber(s?.pendingOrders), deltaText: '—', deltaClass: 'flat' }
  ]
})

// ==================== ECharts 蓝系图表 ====================

function buildTrendOption(): echarts.EChartsOption {
  const dates = trend.value.map((d) => (d.date || '').slice(5))
  const amounts = trend.value.map((d) => Number(d.amount) || 0)
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
        lineStyle: { width: 2.2, color: CHART_BARS[3] },
        itemStyle: { color: CHART_BARS[3] },
        data: orders
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

function buildGrowthOption(): echarts.EChartsOption {
  const dates = userGrowth.value.map((d) => (d.date || '').slice(5))
  const cnts = userGrowth.value.map((d) => Number(d.cnt) || 0)
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
      data: dates,
      axisLine: { lineStyle: { color: '#D8DCE4' } },
      axisTick: { show: false },
      axisLabel: { color: CHART_TEXT, fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: CHART_TEXT, fontSize: 11 },
      splitLine: { lineStyle: { color: '#EEF0F4', type: 'dashed' } }
    },
    series: [
      {
        type: 'line',
        data: cnts,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.4, color: CHART_LINE },
        itemStyle: { color: CHART_LINE },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(45,127,249,0.28)' },
            { offset: 1, color: 'rgba(45,127,249,0.02)' }
          ])
        }
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
  ;[trendChart, distChart, productChart, growthChart].forEach((c) => {
    if (c) {
      try {
        c.dispose()
      } catch {
        /* 容器已被 v-if 移除时忽略 */
      }
    }
  })
  trendChart = distChart = productChart = growthChart = null
}

function initCharts() {
  disposeCharts()
  if (trendRef.value) trendChart = initChart(trendRef.value, buildTrendOption())
  if (distRef.value) distChart = initChart(distRef.value, buildDistOption())
  if (productRef.value) productChart = initChart(productRef.value, buildProductOption())
  if (growthRef.value) growthChart = initChart(growthRef.value, buildGrowthOption())
}

function handleResize() {
  ;[trendChart, distChart, productChart, growthChart].forEach((c) => c?.resize())
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