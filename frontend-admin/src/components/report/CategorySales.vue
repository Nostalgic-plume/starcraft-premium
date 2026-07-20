<template>
  <el-card shadow="hover" class="mb-16">
    <template #header><span class="card-title">🏷️ 分类销售排行</span></template>
    <v-chart v-if="hasData" :option="barOption" autoresize class="chart-box" />
    <el-empty v-else description="暂无数据" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart } from 'echarts/charts'
import { TooltipComponent, GridComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { formatMoney } from '../../api/report'

use([CanvasRenderer, BarChart, TooltipComponent, GridComponent])

const props = defineProps({
  data: { type: Array, default: () => [] }
})

const hasData = computed(() => props.data.length > 0)

const barOption = computed(() => {
  const sorted = [...props.data].sort((a, b) => (b.saleAmount || 0) - (a.saleAmount || 0))
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const p = params[0]
        const d = sorted[p.dataIndex]
        return `${d.categoryName}<br/>销售额：${formatMoney(d.saleAmount)}<br/>销量：${d.saleCount || 0}`
      }
    },
    grid: { left: '3%', right: '8%', bottom: '3%', top: '3%', containLabel: true },
    xAxis: {
      type: 'value',
      axisLabel: {
        formatter: (v) => v >= 10000 ? (v / 10000).toFixed(0) + '万' : v
      }
    },
    yAxis: {
      type: 'category',
      data: [...sorted].reverse().map(d => d.categoryName),
      axisLabel: { fontSize: 12 }
    },
    series: [{
      type: 'bar',
      data: [...sorted].reverse().map(d => ({
        value: d.saleAmount,
        itemStyle: { color: '#e94560', borderRadius: [0, 4, 4, 0] }
      })),
      barWidth: 28,
      label: {
        show: true,
        position: 'right',
        formatter: (p) => formatMoney(p.value, 1),
        fontSize: 11,
        color: '#606266'
      }
    }]
  }
})
</script>

<style scoped>
.card-title { font-size: 15px; font-weight: 600; }
.chart-box { width: 100%; height: 300px; }
</style>
