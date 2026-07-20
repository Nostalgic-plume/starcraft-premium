<template>
  <el-card shadow="hover">
    <template #header><span class="card-title">🏆 商品销售排行 Top{{ data.length }}</span></template>
    <v-chart v-if="hasData" :option="barOption" autoresize class="chart-box" />
    <el-empty v-else description="暂无数据" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { use, graphic } from 'echarts/core'
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
  const names = props.data.map(d =>
    d.productName.length > 10 ? d.productName.slice(0, 10) + '…' : d.productName
  )
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const p = params[0]
        const d = props.data[p.dataIndex]
        return `#${p.dataIndex + 1} ${d.productName}<br/>销售额：${formatMoney(d.saleAmount)}<br/>销量：${d.saleQuantity || 0}`
      }
    },
    grid: { left: '3%', right: '8%', bottom: '3%', top: '3%', containLabel: true },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: { rotate: 30, fontSize: 10, interval: 0 }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (v) => v >= 10000 ? (v / 10000).toFixed(0) + '万' : v
      }
    },
    series: [{
      type: 'bar',
      data: props.data.map((d, i) => ({
        value: d.saleAmount,
        itemStyle: {
          color: new graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#e94560' },
            { offset: 1, color: '#f08080' }
          ]),
          borderRadius: [4, 4, 0, 0]
        }
      })),
      barWidth: 20,
      label: {
        show: true,
        position: 'top',
        formatter: (p) => formatMoney(p.value, 1),
        fontSize: 10,
        color: '#606266'
      }
    }]
  }
})
</script>

<style scoped>
.chart-box { width: 100%; height: 300px; }
.card-title { font-size: 15px; font-weight: 600; }
</style>
