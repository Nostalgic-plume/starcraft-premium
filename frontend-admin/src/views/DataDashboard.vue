<template>
  <div class="dashboard-page new-layout">
    <!-- 加载骨架屏 -->
    <template v-if="loading && !data">
      <div class="hero-zone">
        <el-skeleton :rows="5" animated class="hero-skeleton" />
      </div>
      <el-skeleton :rows="2" animated class="mt-16" />
    </template>

    <!-- 错误状态 -->
    <el-result v-else-if="error" status="error" title="加载失败" :sub-title="error">
      <template #extra>
        <el-button type="primary" @click="loadData">重试</el-button>
      </template>
    </el-result>

    <!-- ================================ -->
    <!-- 正常内容 -->
    <template v-else-if="data">
      <!-- Zone 1: KPI 卡片（紧凑在最顶部） -->
      <section class="kpi-zone">
        <div
          class="kpi-card"
          v-for="k in kpiList"
          :key="k.key"
          :style="{ borderTopColor: k.color }"
        >
          <div class="kpi-value" :style="{ color: k.color }">{{ k.value }}</div>
          <div class="kpi-label">{{ k.label }}</div>
        </div>
      </section>

      <!-- Zone 2: 大趋势图 -->
      <section class="hero-zone">
        <div class="hero-header">
          <div class="hero-title-group">
            <span class="hero-title">GMV &amp; 订单趋势</span>

            <span class="hero-badge">近30日</span>
          </div>
          <div class="hero-stats">
            <span class="hero-stat">
              累计 GMV <strong>{{ formatMoney(data.overview?.totalGmv) }}</strong>
            </span>
            <span class="hero-divider" />
            <span class="hero-stat">
              总订单 <strong>{{ formatNumber(data.overview?.totalOrders) }}</strong>
            </span>
          </div>
        </div>
        <div class="hero-chart-wrap" v-if="heroHasData">
          <v-chart :option="heroOption" autoresize class="hero-chart" />
        </div>
        <el-empty v-else description="暂无趋势数据" />
      </section>

      <!-- Zone 3: 不对称 70/30 分栏 -->
      <section class="split-zone">
        <div class="split-main">
          <CategorySales :data="data.categorySales || []" />
        </div>
        <div class="split-side">
          <UserMetrics
            :active="data.userActive"
            :retain="data.userNewRetain"
            :shop="data.shopDaily"
          />
        </div>
      </section>

      <!-- Zone 4: 底部双栏商品排行 + 订单分布 -->
      <section class="bottom-zone">
        <div class="bottom-col">
          <ProductTop :data="(data.productTop || []).slice(0, 10)" />
        </div>
        <div class="bottom-col">
          <el-card shadow="hover" class="full-height-card">
            <template #header><span class="card-title">🍩 订单状态分布</span></template>
            <v-chart
              v-if="orderPieHasData"
              :option="orderPieOption"
              autoresize
              class="pie-chart"
            />
            <el-empty v-else description="暂无订单数据" />
          </el-card>
        </div>
      </section>
    </template>

    <!-- 空状态 -->
    <el-empty v-else description="暂无数据，点击刷新按钮加载" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { use, graphic } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  TooltipComponent,
  GridComponent,
  LegendComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { Refresh } from '@element-plus/icons-vue'
import { fetchDashboardAll, formatMoney, formatNumber, formatPercent } from '../api/report'
import CategorySales from '../components/report/CategorySales.vue'
import UserMetrics from '../components/report/UserMetrics.vue'
import ProductTop from '../components/report/ProductTop.vue'

// 注册 ECharts 组件
use([
  CanvasRenderer,
  BarChart,
  LineChart,
  PieChart,
  TooltipComponent,
  GridComponent,
  LegendComponent
])

// ====== 数据状态 ======
const data = ref(null)
const loading = ref(false)
const error = ref('')

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const resp = await fetchDashboardAll()
    if (resp.code === 200 || resp.success) {
      data.value = resp.data || resp
    } else {
      error.value = resp.message || '服务器返回异常'
    }
  } catch (e) {
    error.value = e.message || '网络请求失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

// ====== Zone 1: Hero Chart ======
const gmvTrend = computed(() => data.value?.gmvTrend || [])
const heroHasData = computed(() => gmvTrend.value.length > 0)

const heroOption = computed(() => {
  const d = gmvTrend.value
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(26,26,46,0.9)',
      borderColor: '#333',
      textStyle: { color: '#fff' },
      formatter: (params) => {
        const point = d[params[0].dataIndex]
        let html = `<strong style="color:#fff">${point.dt}</strong><br/>`
        params.forEach((p) => {
          const v = p.seriesName === 'GMV' ? formatMoney(p.value) : formatNumber(p.value)
          html += `${p.marker} ${p.seriesName}: ${v}<br/>`
        })
        return html
      }
    },
    legend: {
      top: 0,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: { color: 'rgba(255,255,255,0.8)', fontSize: 12 }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '15%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: d.map((p) => p.dt),
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.15)' } },
      axisLabel: { color: 'rgba(255,255,255,0.55)', fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value',
        name: 'GMV',
        nameTextStyle: { color: 'rgba(255,255,255,0.5)', fontSize: 11 },
        axisLabel: {
          color: 'rgba(255,255,255,0.55)',
          formatter: (v) => (v >= 10000 ? (v / 10000).toFixed(0) + '万' : v)
        },
        splitLine: { lineStyle: { color: 'rgba(255,255,255,0.08)', type: 'dashed' } }
      },
      {
        type: 'value',
        name: '订单',
        nameTextStyle: { color: 'rgba(255,255,255,0.5)', fontSize: 11 },
        axisLabel: { color: 'rgba(255,255,255,0.55)' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: 'GMV',
        type: 'bar',
        yAxisIndex: 0,
        barWidth: '32%',
        itemStyle: {
          color: new graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#e94560' },
            { offset: 1, color: 'rgba(233,69,96,0.25)' }
          ]),
          borderRadius: [3, 3, 0, 0]
        },
        emphasis: {
          itemStyle: { color: '#ff6b81' }
        },
        data: d.map((p) => p.gmv || 0)
      },
      {
        name: '订单总数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { width: 2.5, color: '#f9a825' },
        itemStyle: { color: '#f9a825' },
        areaStyle: {
          color: new graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(249,168,37,0.25)' },
            { offset: 1, color: 'rgba(249,168,37,0.01)' }
          ])
        },
        data: d.map((p) => p.orderCount || 0)
      },
      {
        name: '已支付',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'diamond',
        symbolSize: 4,
        lineStyle: { width: 2, color: '#67c23a', type: 'dashed' },
        itemStyle: { color: '#67c23a' },
        data: d.map((p) => p.paidOrderCount || 0)
      }
    ]
  }
})

// ====== Zone 2: KPI Cards ======
const kpiList = computed(() => {
  const gd = data.value?.gmvDaily
  const od = data.value?.orderStats
  const ov = data.value?.overview
  const items = []
  items.push({
    key: 'gmv',
    label: '今日 GMV',
    value: gd?.gmv != null ? formatMoney(gd.gmv) : '—',
    color: '#e94560'
  })
  items.push({
    key: 'orders',
    label: '今日订单',
    value: gd?.orderCount != null ? formatNumber(gd.orderCount) : '—',
    color: '#f9a825'
  })
  items.push({
    key: 'conv',
    label: '转化率',
    value: od?.conversionRate != null ? formatPercent(od.conversionRate) : '—',
    color: '#67c23a'
  })
  const refundRate =
    od?.totalOrders > 0
      ? ((od.refundOrders / od.totalOrders) * 100).toFixed(1) + '%'
      : '—'
  items.push({
    key: 'refund',
    label: '退款率',
    value: refundRate,
    color: '#909399'
  })
  return items
})

// ====== Zone 4: Order Pie ======
const orderStats = computed(() => data.value?.orderStats)
const orderPieHasData = computed(() => {
  const s = orderStats.value
  return s && s.totalOrders > 0
})

const orderPieOption = computed(() => {
  const s = orderStats.value
  if (!s) return {}
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 单 ({d}%)' },
    legend: {
      bottom: 0,
      orient: 'horizontal',
      itemWidth: 10,
      itemHeight: 10
    },
    series: [
      {
        type: 'pie',
        radius: ['40%', '65%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}', fontSize: 11 },
        emphasis: {
          label: { show: true, fontSize: 14, fontWeight: 'bold' }
        },
        data: [
          { value: s.paidOrders, name: '已支付', itemStyle: { color: '#67c23a' } },
          { value: s.shippedOrders, name: '已发货', itemStyle: { color: '#1a1a2e' } },
          { value: s.completedOrders, name: '已完成', itemStyle: { color: '#909399' } },
          { value: s.cancelledOrders, name: '已取消', itemStyle: { color: '#e6a23c' } },
          { value: s.refundOrders, name: '已退款', itemStyle: { color: '#f56c6c' } }
        ].filter((d) => d.value > 0)
      }
    ]
  }
})
</script>

<style scoped>
/* ====== 容器 ====== */
.new-layout {
  padding: 20px;
}

/* ====== Zone 1: Hero 趋势图 ====== */
.hero-zone {
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  border-radius: 12px;
  padding: 24px 24px 16px;
  margin-bottom: 16px;
  min-height: 400px;
}
.hero-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.hero-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
}
.hero-title {
  font-size: 18px;
  font-weight: 700;
  color: #fff;
}
.hero-badge {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.6);
  background: rgba(255, 255, 255, 0.1);
  padding: 2px 10px;
  border-radius: 10px;
}
.hero-stats {
  display: flex;
  align-items: center;
  gap: 12px;
}
.hero-stat {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
}
.hero-stat strong {
  color: #e94560;
  font-weight: 700;
  margin-left: 4px;
}
.hero-divider {
  width: 1px;
  height: 14px;
  background: rgba(255, 255, 255, 0.15);
}
.hero-chart-wrap {
  width: 100%;
}
.hero-chart {
  width: 100%;
  height: 44vh;
  min-height: 360px;
}
.hero-skeleton {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  padding: 24px;
}

/* ====== Zone 2: KPI 行 ====== */
.kpi-zone {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.kpi-card {
  flex: 1;
  background: #fff;
  border-radius: 10px;
  padding: 16px 12px;
  text-align: center;
  border-top: 3px solid #e94560;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  transition: transform 0.2s, box-shadow 0.2s;
  cursor: default;
}
.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}
.kpi-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
}
.kpi-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

/* ====== Zone 3: 不对称 70/30 ====== */
.split-zone {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}
.split-main {
  flex: 7;
  min-width: 0;
}
.split-side {
  flex: 3;
  min-width: 0;
}

/* ====== Zone 4: 底部双栏 ====== */
.bottom-zone {
  display: flex;
  gap: 16px;
}
.bottom-col {
  flex: 1;
  min-width: 0;
}
.full-height-card {
  height: 100%;
}
.full-height-card :deep(.el-card__body) {
  height: calc(100% - 44px);
  display: flex;
  align-items: center;
  justify-content: center;
}
.pie-chart {
  width: 100%;
  height: 300px;
}

/* 卡片标题复用 */
:deep(.card-title) {
  font-size: 15px;
  font-weight: 600;
}

/* 工具类 */
.mt-16 {
  margin-top: 16px;
}
</style>
