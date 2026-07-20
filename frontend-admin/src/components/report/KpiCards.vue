<template>
  <el-row :gutter="16" class="mb-16">
    <el-col :xs="12" :sm="8" :md="6" :lg="4" :xl="4" v-for="card in cards" :key="card.key">
      <el-card shadow="hover" :body-style="{ padding: '16px' }">
        <div class="kpi-inner">
          <el-icon :size="28" :color="card.color" class="kpi-icon"><component :is="card.icon" /></el-icon>
          <div class="kpi-body">
            <div class="kpi-value" :style="{ color: card.color }">{{ card.value }}</div>
            <div class="kpi-label">{{ card.label }}</div>
          </div>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { computed } from 'vue'
import { User, Goods, ShoppingCart, Money, Tickets } from '@element-plus/icons-vue'
import { formatMoney, formatNumber } from '../../api/report'

const props = defineProps({
  data: { type: Object, default: null }
})

const cards = computed(() => {
  const d = props.data
  if (!d) return []
  return [
    { key: 'users', label: '总用户', value: formatNumber(d.totalUsers), icon: User, color: '#1a1a2e' },
    { key: 'products', label: '总商品', value: formatNumber(d.totalProducts), icon: Goods, color: '#67c23a' },
    { key: 'orders', label: '总订单', value: formatNumber(d.totalOrders), icon: ShoppingCart, color: '#e6a23c' },
    { key: 'gmv', label: '总 GMV', value: formatMoney(d.totalGmv), icon: Money, color: '#f56c6c' },
    { key: 'avg', label: '客单价', value: '¥' + formatNumber(d.avgOrderPrice), icon: Tickets, color: '#909399' }
  ]
})
</script>

<style scoped>
.kpi-inner { display: flex; align-items: center; gap: 12px; }
.kpi-icon { flex-shrink: 0; }
.kpi-body { flex: 1; min-width: 0; }
.kpi-value { font-size: 22px; font-weight: 700; line-height: 1.2; }
.kpi-label { font-size: 12px; color: #909399; margin-top: 2px; }
</style>
