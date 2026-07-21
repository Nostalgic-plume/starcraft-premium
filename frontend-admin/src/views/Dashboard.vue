<template>
  <div class="page-container">
    <h1 class="page-title">控制台概览</h1>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="mb-24">
      <el-col :xs="12" :sm="8" :md="6" :lg="4" v-for="card in statCards" :key="card.label">
        <div class="stat-card" :style="statCardStyle(card)">
          <div class="stat-icon-wrap" :style="{ background: card.iconBg }">
            <span class="stat-icon">{{ card.icon }}</span>
          </div>
          <div class="stat-body">
            <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="16">
      <el-col :xs="24" :lg="14" class="mb-16">
        <div class="card-elevated">
          <div class="chart-header">
            <h3 class="chart-title">销售额趋势</h3>
            <el-radio-group v-model="trendDays" size="small">
              <el-radio-button :value="7">7天</el-radio-button>
              <el-radio-button :value="30">30天</el-radio-button>
            </el-radio-group>
          </div>
          <div ref="trendChartRef" class="chart-box"></div>
        </div>
      </el-col>
      <el-col :xs="24" :lg="10" class="mb-16">
        <div class="card-elevated">
          <div class="chart-header">
            <h3 class="chart-title">订单分布</h3>
          </div>
          <div ref="orderChartRef" class="chart-box"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="12" class="mb-16">
        <div class="card-elevated">
          <div class="chart-header">
            <h3 class="chart-title">商品销量排行 TOP10</h3>
          </div>
          <div ref="productChartRef" class="chart-box"></div>
        </div>
      </el-col>
      <el-col :xs="24" :lg="12" class="mb-16">
        <div class="card-elevated">
          <div class="chart-header">
            <h3 class="chart-title">用户增长（近30天）</h3>
          </div>
          <div ref="userChartRef" class="chart-box"></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { dashboardApi } from '@/api/dashboard'

// 品牌色
const NAVY = '#1a1a2e'
const CORAL = '#e94560'
const GOLD = '#f9a825'
const TEAL = '#00b894'
const PURPLE = '#6c5ce7'

const statCards = ref([
  { label: '用户总数', value: 0, color: '#2d3436', icon: '👥', iconBg: 'var(--ds-coral-gradient)' },
  { label: '商品总数', value: 0, color: '#2d3436', icon: '📦', iconBg: 'var(--ds-coral-gradient)' },
  { label: '订单总数', value: 0, color: '#2d3436', icon: '📋', iconBg: 'var(--ds-coral-gradient)' },
  { label: '销售额', value: '¥0', color: CORAL, icon: '💰', iconBg: 'var(--ds-coral-gradient)' },
  { label: '待支付', value: 0, color: '#2d3436', icon: '⏳', iconBg: '#f2f4f4' }
])

const trendDays = ref(7)
const trendChartRef = ref(null)
const productChartRef = ref(null)
const userChartRef = ref(null)
const orderChartRef = ref(null)

let trendChart = null
let productChart = null
let userChart = null
let orderChart = null

function disposeCharts() {
  [trendChart, productChart, userChart, orderChart].forEach(c => c?.dispose())
}

function statCardStyle(card) {
  return { borderTop: `3px solid ${card.color}` }
}

function initChart(el, option) {
  if (!el.value) return null
  const chart = echarts.init(el.value)
  chart.setOption(option)
  return chart
}

const handleResize = () => {
  [trendChart, productChart, userChart, orderChart].forEach(c => c?.resize())
}

watch(trendDays, async (days) => {
  if (!trendChartRef.value) return
  const trend = await dashboardApi.getSalesTrend(days)
  if (!trend?.length) return
  trendChart?.setOption({
    xAxis: { data: trend.map(d => (d.date || '').slice(5)) },
    series: [{ data: trend.map(d => Number(d.amount) || 0) }]
  })
})

onMounted(async () => {
  try {
    const [stats, trend, sales, growth, distribution] = await Promise.all([
      dashboardApi.getStats(),
      dashboardApi.getSalesTrend(7),
      dashboardApi.getProductSales(),
      dashboardApi.getUserGrowth(),
      dashboardApi.getOrderDistribution()
    ])

    const s = stats?.data || {}
    statCards.value = [
      { label: '用户总数', value: Number(s.totalUsers) || 0, color: NAVY, icon: '👥', iconBg: 'var(--ds-coral-gradient)' },
      { label: '商品总数', value: Number(s.totalProducts) || 0, color: '#27ae60', icon: '📦', iconBg: 'var(--ds-coral-gradient)' },
      { label: '订单总数', value: Number(s.totalOrders) || 0, color: GOLD, icon: '📋', iconBg: 'var(--ds-coral-gradient)' },
      { label: '销售额', value: '¥' + Number(s.totalSales || 0).toFixed(2), color: CORAL, icon: '💰', iconBg: 'var(--ds-coral-gradient)' },
      { label: '待支付', value: Number(s.pendingOrders) || 0, color: '#636e72', icon: '⏳', iconBg: '#f2f4f4' }
    ]

    // 销售额趋势
    const trendData = trend?.data || trend || []
    if (trendData.length) {
      trendChart = initChart(trendChartRef, {
        tooltip: { trigger: 'axis', valueFormatter: v => '¥' + (Number(v) || 0).toFixed(2) },
        grid: { left: 60, right: 20, top: 20, bottom: 30 },
        xAxis: { type: 'category', data: trendData.map(d => (d.date || '').slice(5)), axisLabel: { color: '#b2bec3' }, axisLine: { show: false }, axisTick: { show: false } },
        yAxis: { type: 'value', axisLabel: { color: '#b2bec3', formatter: '¥{value}' }, splitLine: { lineStyle: { color: '#f0eee9', type: 'dashed' } } },
        series: [{ type: 'line', data: trendData.map(d => Number(d.amount) || 0), smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2.5, color: NAVY }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(26,26,46,0.1)' }, { offset: 1, color: 'rgba(26,26,46,0.01)' }]) } }]
      })
    }

    // 商品销量排行
    const salesData = sales?.data || sales || []
    if (salesData.length) {
      const top10 = salesData.slice(0, 10).reverse()
      productChart = initChart(productChartRef, {
        tooltip: { trigger: 'axis' },
        grid: { left: 100, right: 20, top: 16, bottom: 30 },
        xAxis: { type: 'value', axisLabel: { color: '#b2bec3' }, splitLine: { lineStyle: { color: '#f0eee9', type: 'dashed' } } },
        yAxis: { type: 'category', data: top10.map(d => d.name), axisLabel: { color: '#636e72', fontSize: 11 } },
        series: [{ type: 'bar', data: top10.map(d => Number(d.totalSales) || 0), itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [{ offset: 0, color: CORAL }, { offset: 1, color: GOLD }]), borderRadius: [0, 4, 4, 0] } }]
      })
    }

    // 用户增长
    const growthData = growth?.data || growth || []
    if (growthData.length) {
      userChart = initChart(userChartRef, {
        tooltip: { trigger: 'axis' },
        grid: { left: 50, right: 20, top: 16, bottom: 30 },
        xAxis: { type: 'category', data: growthData.map(d => (d.date || '').slice(5)), axisLabel: { color: '#b2bec3', fontSize: 10 }, axisLine: { show: false }, axisTick: { show: false } },
        yAxis: { type: 'value', axisLabel: { color: '#b2bec3' }, splitLine: { lineStyle: { color: '#f0eee9', type: 'dashed' } } },
        series: [{ type: 'line', data: growthData.map(d => Number(d.cnt) || 0), smooth: true, symbol: 'none', lineStyle: { width: 2, color: GOLD }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(249,168,37,0.1)' }, { offset: 1, color: 'rgba(249,168,37,0.01)' }]) } }]
      })
    }

    // 订单分布
    const distData = distribution?.data || distribution || []
    if (distData.length) {
      const statusNameMap = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已收货', 4: '已完成', 5: '已取消', 6: '已退款' }
      const colorMap = ['#b2bec3', '#6c5ce7', '#f39c12', '#00b894', '#27ae60', '#e94560', '#636e72']
      orderChart = initChart(orderChartRef, {
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        series: [{
          type: 'pie', radius: ['42%', '68%'], center: ['50%', '50%'],
          data: distData.map((d, i) => ({ name: statusNameMap[d.status] || '未知', value: Number(d.cnt) || 0, itemStyle: { color: colorMap[i % colorMap.length] } })),
          label: { color: '#636e72', fontSize: 12 },
          labelLine: { lineStyle: { color: '#e8e6e1' } },
          itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 }
        }]
      })
    }

    window.addEventListener('resize', handleResize)
  } catch (e) {
    console.error('Dashboard load failed:', e)
  }
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  disposeCharts()
})
</script>

<style scoped>
.page-container {
  max-width: none;
}
/* ====== 统计卡片 ====== */
.stat-card {
  background: var(--ds-surface, #fff);
  border: 1px solid var(--ds-border-light, #f0eee9);
  border-radius: var(--ds-radius-lg, 16px);
  padding: 18px;
  display: flex;
  align-items: center;
  gap: 14px;
  transition: transform 0.2s ease, box-shadow 0.3s ease;
  cursor: default;
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--ds-shadow-md, 0 4px 16px rgba(26,26,46,0.08));
}

.stat-icon-wrap {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-icon { font-size: 22px; line-height: 1; }

.stat-body { flex: 1; min-width: 0; }
.stat-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  margin-bottom: 2px;
}
.stat-label {
  font-size: 12px;
  color: var(--ds-text-muted, #b2bec3);
  letter-spacing: 0.3px;
}

/* ====== 图表容器 ====== */
.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.chart-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text, #1a1a2e);
}
.chart-box { height: 35vh; min-height: 220px; width: 100%; }
</style>
