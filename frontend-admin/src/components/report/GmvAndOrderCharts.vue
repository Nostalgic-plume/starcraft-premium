<template>
  <el-row :gutter="16" class="mb-16">
    <!-- 左：GMV 概览 -->
    <el-col :span="12">
      <el-card shadow="hover">
        <template #header><span class="card-title">📈 GMV 概览</span></template>
        <div class="gmv-main">
          <div class="gmv-big">{{ formatMoney(gmvData?.gmv) }}</div>
          <el-row :gutter="12">
            <el-col :span="12" v-if="gmvData">
              <div class="gmv-item">
                <span class="gmv-item-label">订单总数</span>
                <span class="gmv-item-value">{{ formatNumber(gmvData.orderCount) }}</span>
              </div>
            </el-col>
            <el-col :span="12" v-if="gmvData">
              <div class="gmv-item">
                <span class="gmv-item-label">已支付订单</span>
                <span class="gmv-item-value">{{ formatNumber(gmvData.paidOrderCount) }}</span>
              </div>
            </el-col>
            <el-col :span="12" v-if="gmvData">
              <div class="gmv-item">
                <span class="gmv-item-label">支付用户数</span>
                <span class="gmv-item-value">{{ formatNumber(gmvData.paidUserCount) }}</span>
              </div>
            </el-col>
            <el-col :span="12" v-if="gmvData">
              <div class="gmv-item">
                <span class="gmv-item-label">平均订单金额</span>
                <span class="gmv-item-value">¥{{ formatNumber(gmvData.avgOrderAmount) }}</span>
              </div>
            </el-col>
          </el-row>
        </div>
      </el-card>
    </el-col>

    <!-- 右：订单状态分布 -->
    <el-col :span="12">
      <el-card shadow="hover">
        <template #header><span class="card-title">🍩 订单状态分布</span></template>
        <v-chart v-if="hasPieData" :option="pieOption" autoresize class="chart-box" />
        <el-empty v-else description="暂无数据" />
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { formatMoney, formatNumber } from '../../api/report'

use([CanvasRenderer, PieChart, TooltipComponent, LegendComponent])

const props = defineProps({
  gmvData: { type: Object, default: null },
  orderData: { type: Object, default: null }
})

const gmvData = computed(() => props.gmvData)
const hasPieData = computed(() => props.orderData && props.orderData.totalOrders > 0)

const pieOption = computed(() => {
  const od = props.orderData
  if (!od) return {}
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 单 ({d}%)' },
    legend: { bottom: 0, orient: 'horizontal', itemWidth: 10, itemHeight: 10 },
    series: [{
      type: 'pie',
      radius: ['40%', '65%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}', fontSize: 11 },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
      data: [
        { value: od.paidOrders, name: '已支付', itemStyle: { color: '#67c23a' } },
        { value: od.shippedOrders, name: '已发货', itemStyle: { color: '#1a1a2e' } },
        { value: od.completedOrders, name: '已完成', itemStyle: { color: '#909399' } },
        { value: od.cancelledOrders, name: '已取消', itemStyle: { color: '#e6a23c' } },
        { value: od.refundOrders, name: '已退款', itemStyle: { color: '#f56c6c' } }
      ].filter(d => d.value > 0)
    }]
  }
})
</script>

<style scoped>
.card-title { font-size: 15px; font-weight: 600; }
.gmv-main { text-align: center; }
.gmv-big { font-size: 32px; font-weight: 700; color: #f56c6c; margin-bottom: 16px; }
.gmv-item { display: flex; flex-direction: column; align-items: center; padding: 8px; background: #fafafa; border-radius: 8px; margin-bottom: 8px; }
.gmv-item-label { font-size: 12px; color: #909399; }
.gmv-item-value { font-size: 16px; font-weight: 600; color: #303133; margin-top: 2px; }
.chart-box { width: 100%; height: 260px; }
</style>
