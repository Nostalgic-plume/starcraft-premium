<template>
  <div class="page-container">
    <h1 class="page-title">店铺概览</h1>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="mb-24">
      <el-col :xs="12" :sm="6" v-for="card in statCards" :key="card.label">
        <div class="stat-card" :style="{ borderTop: '3px solid ' + card.color }">
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
      <!-- 销售额趋势 -->
      <el-col :xs="24" :lg="14" class="mb-16">
        <div class="card">
          <div class="chart-header">
            <h3 class="chart-title">销售额趋势</h3>
            <el-radio-group v-model="trendDays" size="small">
              <el-radio-button :value="7">7天</el-radio-button>
              <el-radio-button :value="30">30天</el-radio-button>
            </el-radio-group>
          </div>
          <div ref="trendRef" class="chart-box"></div>
          <div v-if="chartEmpty" class="chart-empty-box">
            <el-empty description="暂无销售数据" :image-size="60" />
          </div>
        </div>
      </el-col>

      <!-- 订单分布 -->
      <el-col :xs="24" :lg="10" class="mb-16">
        <div class="card">
          <div class="chart-header">
            <h3 class="chart-title">订单分布</h3>
          </div>
          <div ref="orderDistRef" class="chart-box"></div>
          <div v-if="orderDistEmpty" class="chart-empty-box">
            <el-empty description="暂无订单" :image-size="60" />
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 商品销量排行 -->
      <el-col :xs="24" :lg="12" class="mb-16">
        <div class="card">
          <div class="chart-header">
            <h3 class="chart-title">商品销量排行 TOP5</h3>
          </div>
          <div ref="productRankRef" class="chart-box"></div>
          <div v-if="productRankEmpty" class="chart-empty-box">
            <el-empty description="暂无商品" :image-size="60" />
          </div>
        </div>
      </el-col>

      <!-- 每日订单数 -->
      <el-col :xs="24" :lg="12" class="mb-16">
        <div class="card">
          <div class="chart-header">
            <h3 class="chart-title">每日订单数</h3>
          </div>
          <div ref="dailyOrdersRef" class="chart-box"></div>
          <div v-if="dailyOrdersEmpty" class="chart-empty-box">
            <el-empty description="暂无订单" :image-size="60" />
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getDashboardStats, getSalesTrend } from '@/api/dashboard'
import request from '@/api/request'

const NAVY = '#1a1a2e'
const CORAL = '#e94560'
const GOLD = '#f9a825'
const TEAL = '#00b894'

/* ====== 统计 ====== */
const stats = ref({ totalProducts: 0, onShelf: 0, totalOrders: 0, pendingShip: 0 })

const statCards = computed(() => [
  { label: '本店商品', value: stats.value.totalProducts, icon: '📦', color: NAVY, iconBg: '#e8e8ef' },
  { label: '上架中',   value: stats.value.onShelf,       icon: '✅', color: '#27ae60', iconBg: '#eafaf1' },
  { label: '订单总数', value: stats.value.totalOrders,   icon: '📋', color: GOLD, iconBg: '#fef5e7' },
  { label: '待发货',   value: stats.value.pendingShip,   icon: '🚚', color: CORAL, iconBg: '#fdedec' }
])

async function loadStats() {
  stats.value = await getDashboardStats()
}

/* ====== 图表引用 ====== */
const trendRef = ref(null)
const orderDistRef = ref(null)
const productRankRef = ref(null)
const dailyOrdersRef = ref(null)

let trendChart = null
let orderDistChart = null
let productRankChart = null
let dailyOrdersChart = null

function disposeAll() {
  [trendChart, orderDistChart, productRankChart, dailyOrdersChart].forEach(c => c?.dispose())
}

const handleResize = () => {
  [trendChart, orderDistChart, productRankChart, dailyOrdersChart].forEach(c => c?.resize())
}

/* ====== 销售额趋势 ====== */
const trendDays = ref(7)
const trendData = ref([])
const chartEmpty = ref(false)

async function loadTrend() {
  trendData.value = await getSalesTrend(trendDays.value)
  chartEmpty.value = trendData.value.length === 0 || trendData.value.every(d => d.amount === 0)
  renderTrend()
}

function renderTrend() {
  if (!trendRef.value) return
  if (!trendChart) trendChart = echarts.init(trendRef.value)
  trendChart.setOption({
    tooltip: { trigger: 'axis', valueFormatter: v => '¥' + (v || 0).toFixed(2) },
    grid: { left: 60, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: trendData.value.map(d => (d.date || '').slice(5)), axisLabel: { color: '#b2bec3' }, axisLine: { show: false }, axisTick: { show: false } },
    yAxis: { type: 'value', axisLabel: { color: '#b2bec3', formatter: '¥{value}' }, splitLine: { lineStyle: { color: '#f0eee9', type: 'dashed' } } },
    series: [{ type: 'line', data: trendData.value.map(d => d.amount || 0), smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2.5, color: NAVY }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(26,26,46,0.08)' }, { offset: 1, color: 'rgba(26,26,46,0.01)' }]) } }]
  })
}

/* ====== 订单分布 ====== */
const orderDistEmpty = ref(true)
const statusNameMap = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已收货', 4: '已完成', 5: '已取消', 6: '已退款' }
const distColors = ['#b2bec3', '#6c5ce7', '#f39c12', '#00b894', '#27ae60', '#e94560', '#636e72']

async function loadOrderDist() {
  try {
    const res = await request.get('/merchant/order/page', { params: { page: 1, pageSize: 999 } })
    const orders = res.data?.list || []
    const countMap = {}
    orders.forEach(o => { countMap[o.status] = (countMap[o.status] || 0) + 1 })
    const data = Object.entries(countMap).map(([k, v], i) => ({
      name: statusNameMap[k] || '未知',
      value: v,
      itemStyle: { color: distColors[i % distColors.length] }
    }))
    orderDistEmpty.value = data.length === 0
    if (orderDistRef.value && data.length) {
      if (!orderDistChart) orderDistChart = echarts.init(orderDistRef.value)
      orderDistChart.setOption({
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        series: [{ type: 'pie', radius: ['42%', '68%'], center: ['50%', '50%'], data, label: { color: '#636e72', fontSize: 12 }, labelLine: { lineStyle: { color: '#e8e6e1' } }, itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 } }]
      })
    }
  } catch (e) { console.error('加载订单分布失败:', e); orderDistEmpty.value = true }
}

/* ====== 商品销量排行 TOP5 ====== */
const productRankEmpty = ref(true)

async function loadProductRank() {
  try {
    const res = await request.get('/merchant/product/page', { params: { page: 1, pageSize: 999 } })
    const products = res.data?.list || []
    const withSales = products.filter(p => p.totalSales > 0).sort((a, b) => b.totalSales - a.totalSales).slice(0, 5)
    productRankEmpty.value = withSales.length === 0
    if (productRankRef.value && withSales.length) {
      if (!productRankChart) productRankChart = echarts.init(productRankRef.value)
      const names = withSales.map(p => p.name.length > 8 ? p.name.slice(0, 8) + '…' : p.name).reverse()
      const vals = withSales.map(p => p.totalSales).reverse()
      productRankChart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 80, right: 20, top: 16, bottom: 24 },
        xAxis: { type: 'value', axisLabel: { color: '#b2bec3' }, splitLine: { lineStyle: { color: '#f0eee9', type: 'dashed' } } },
        yAxis: { type: 'category', data: names, axisLabel: { color: '#636e72', fontSize: 11 } },
        series: [{ type: 'bar', data: vals, itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [{ offset: 0, color: CORAL }, { offset: 1, color: '#ff6b81' }]), borderRadius: [0, 4, 4, 0] }, label: { show: true, position: 'right', formatter: v => v.value + '件', color: '#636e72', fontSize: 11 } }]
      })
    }
  } catch (e) { console.error('加载商品排行失败:', e); productRankEmpty.value = true }
}

/* ====== 每日订单数 ====== */
const dailyOrdersEmpty = ref(true)

async function loadDailyOrders() {
  try {
    const res = await request.get('/merchant/order/page', { params: { page: 1, pageSize: 999 } })
    const orders = res.data?.list || []
    const dates = []
    for (let i = 6; i >= 0; i--) {
      const d = new Date()
      d.setDate(d.getDate() - i)
      dates.push(d.toISOString().slice(0, 10))
    }
    const dailyMap = {}
    orders.forEach(o => {
      const date = o.createTime?.slice(0, 10)
      if (date) dailyMap[date] = (dailyMap[date] || 0) + 1
    })
    const data = dates.map(d => ({ date: d.slice(5), count: dailyMap[d] || 0 }))
    dailyOrdersEmpty.value = data.every(d => d.count === 0)
    if (dailyOrdersRef.value) {
      if (!dailyOrdersChart) dailyOrdersChart = echarts.init(dailyOrdersRef.value)
      dailyOrdersChart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 50, right: 20, top: 16, bottom: 30 },
        xAxis: { type: 'category', data: data.map(d => d.date), axisLabel: { color: '#b2bec3' }, axisLine: { show: false }, axisTick: { show: false } },
        yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#b2bec3' }, splitLine: { lineStyle: { color: '#f0eee9', type: 'dashed' } } },
        series: [{ type: 'bar', data: data.map(d => d.count), itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: GOLD }, { offset: 1, color: '#fdcb6e' }]), borderRadius: [4, 4, 0, 0] } }]
      })
    }
  } catch (e) { console.error('加载每日订单失败:', e); dailyOrdersEmpty.value = true }
}

/* ====== 生命周期 ====== */
onMounted(async () => {
  await loadStats()
  await Promise.all([loadTrend(), loadOrderDist(), loadProductRank(), loadDailyOrders()])
  await nextTick()
  renderTrend()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  disposeAll()
})
</script>

<style scoped>
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

/* ====== 图表 ====== */
.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.chart-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ds-text, #1a1a2e);
}
.chart-box { height: 280px; width: 100%; }
.chart-empty-box {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 280px;
}
</style>
