<template>
  <el-card shadow="hover">
    <template #header><span class="card-title">📊 订单状态日趋势</span></template>
    <v-chart v-if="hasData" :option="option" autoresize class="chart-box" />
    <el-empty v-else description="暂无日趋势数据" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { formatNumber } from '../../api/report'

use([CanvasRenderer, LineChart, TooltipComponent, GridComponent, LegendComponent])

const props = defineProps({
  data: { type: Array, default: () => [] }
})

const hasData = computed(() => props.data.length > 0)

const option = computed(() => {
  const d = props.data
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const point = d[params[0].dataIndex]
        let html = `<strong>${point.dt}</strong><br/>`
        html += `总订单：${formatNumber(point.totalOrders)}<br/>`
        params.forEach(p => {
          html += `${p.marker} ${p.seriesName}: ${formatNumber(p.value)}<br/>`
        })
        return html
      }
    },
    legend: { top: 0, itemWidth: 12, itemHeight: 8 },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: {
      type: 'category',
      data: d.map(p => p.dt),
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: (v) => v }
    },
    series: [
      {
        name: '已支付',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color: '#67c23a' },
        itemStyle: { color: '#67c23a' },
        areaStyle: {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(103,194,58,0.4)' },
              { offset: 1, color: 'rgba(103,194,58,0.02)' }
            ] }
        },
        stack: 'total',
        data: d.map(p => p.paidOrders || 0)
      },
      {
        name: '已发货',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color: '#1a1a2e' },
        itemStyle: { color: '#1a1a2e' },
        areaStyle: {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(26,26,46,0.3)' },
              { offset: 1, color: 'rgba(26,26,46,0.01)' }
            ] }
        },
        stack: 'total',
        data: d.map(p => p.shippedOrders || 0)
      },
      {
        name: '已完成',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color: '#909399' },
        itemStyle: { color: '#909399' },
        areaStyle: {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(144,147,153,0.3)' },
              { offset: 1, color: 'rgba(144,147,153,0.01)' }
            ] }
        },
        stack: 'total',
        data: d.map(p => p.completedOrders || 0)
      },
      {
        name: '已取消',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color: '#e6a23c' },
        itemStyle: { color: '#e6a23c' },
        areaStyle: {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(230,162,60,0.2)' },
              { offset: 1, color: 'rgba(230,162,60,0.01)' }
            ] }
        },
        stack: 'total',
        data: d.map(p => p.cancelledOrders || 0)
      }
    ]
  }
})
</script>

<style scoped>
.card-title { font-size: 14px; font-weight: 600; color: var(--ds-text, #1a1a2e); }
.chart-box { width: 100%; height: 280px; }
</style>
