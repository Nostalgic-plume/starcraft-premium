<template>
  <el-card shadow="hover">
    <template #header><span class="card-title">📈 GMV & 订单日趋势</span></template>
    <v-chart v-if="hasData" :option="option" autoresize class="chart-box" />
    <el-empty v-else description="暂无日趋势数据" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { formatMoney, formatNumber } from '../../api/report'

use([CanvasRenderer, LineChart, BarChart, TooltipComponent, GridComponent, LegendComponent])

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
        params.forEach(p => {
          html += `${p.marker} ${p.seriesName}: ${p.seriesName === 'GMV' ? formatMoney(p.value) : formatNumber(p.value)}<br/>`
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
    yAxis: [
      {
        type: 'value',
        name: 'GMV',
        axisLabel: { formatter: (v) => v >= 10000 ? (v / 10000).toFixed(0) + '万' : v }
      },
      {
        type: 'value',
        name: '单量',
        axisLabel: { formatter: (v) => v }
      }
    ],
    series: [
      {
        name: 'GMV',
        type: 'bar',
        yAxisIndex: 0,
        barWidth: '40%',
        itemStyle: { color: '#f56c6c', borderRadius: [4, 4, 0, 0] },
        data: d.map(p => p.gmv || 0)
      },
      {
        name: '订单总数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2, color: '#1a1a2e' },
        itemStyle: { color: '#1a1a2e' },
        data: d.map(p => p.orderCount || 0)
      },
      {
        name: '已支付订单',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'diamond',
        symbolSize: 6,
        lineStyle: { width: 2, color: '#67c23a', type: 'dashed' },
        itemStyle: { color: '#67c23a' },
        data: d.map(p => p.paidOrderCount || 0)
      }
    ]
  }
})
</script>

<style scoped>
.card-title { font-size: 14px; font-weight: 600; color: var(--ds-text, #1a1a2e); }
.chart-box { width: 100%; height: 280px; }
</style>
