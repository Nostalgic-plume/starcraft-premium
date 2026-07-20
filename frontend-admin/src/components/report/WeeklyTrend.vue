<template>
  <el-card shadow="hover">
    <template #header><span class="card-title">📊 周趋势</span></template>
    <v-chart v-if="hasData" :option="lineOption" autoresize class="chart-box" />
    <el-empty v-else description="暂无周趋势数据（需积累多周数据）" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { formatMoney } from '../../api/report'

use([CanvasRenderer, LineChart, TooltipComponent, GridComponent, LegendComponent])

const props = defineProps({
  data: { type: Array, default: () => [] }
})

const hasData = computed(() => props.data.length > 0)

const lineOption = computed(() => {
  const sorted = [...props.data].reverse()
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const d = sorted[params[0].dataIndex]
        return `${d.weekStart} ~ ${d.weekEnd}<br/>${params.map(p => `${p.marker} ${p.seriesName}: ${p.value}`).join('<br/>')}`
      }
    },
    legend: { top: 0, itemWidth: 12, itemHeight: 8 },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: {
      type: 'category',
      data: sorted.map(d => d.weekStart),
      axisLabel: { fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value',
        name: 'GMV',
        axisLabel: { formatter: (v) => v >= 10000 ? (v / 10000).toFixed(0) + '万' : v }
      },
      {
        type: 'value',
        name: '订单数',
        axisLabel: { formatter: (v) => v }
      }
    ],
    series: [
      {
        name: 'GMV',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { width: 3, color: '#f56c6c' },
        itemStyle: { color: '#f56c6c' },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(245,108,108,0.25)' },
              { offset: 1, color: 'rgba(245,108,108,0.02)' }
            ]
          }
        },
        data: sorted.map(d => d.gmv || 0)
      },
      {
        name: '订单数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'diamond',
        symbolSize: 8,
        lineStyle: { width: 2, color: '#e94560', type: 'dashed' },
        itemStyle: { color: '#e94560' },
        data: sorted.map(d => d.orderCount || 0)
      }
    ]
  }
})
</script>

<style scoped>
.chart-box { width: 100%; height: 280px; }
.card-title { font-size: 15px; font-weight: 600; }
</style>
