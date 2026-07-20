<template>
  <el-card shadow="hover" class="mb-16">
    <template #header><span class="card-title">👤 用户指标</span></template>
    <el-row :gutter="16">
      <el-col :span="12">
        <div class="metric-card">
          <div class="metric-label">日活跃用户 (DAU)</div>
          <div class="metric-value" style="color: var(--ds-navy, #1a1a2e)">{{ active?.dau ?? '—' }}</div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="metric-card">
          <div class="metric-label">下单用户数</div>
          <div class="metric-value" style="color: #67c23a">{{ active?.orderUserCount ?? '—' }}</div>
        </div>
      </el-col>
    </el-row>
    <el-divider style="margin: 12px 0" />
    <div class="retain-section">
      <div class="retain-header">新增用户：<strong style="color:#e6a23c">{{ retain?.newUsers ?? '—' }}</strong></div>
      <el-row :gutter="16" style="margin-top:8px">
        <el-col :span="8">
          <div class="metric-card">
            <div class="metric-label">Day1 留存用户</div>
            <div class="metric-value" style="color: var(--ds-navy, #1a1a2e); font-size:22px">{{ retain?.day1RetainUsers ?? '—' }}</div>
            <div class="metric-sub">{{ formatPercent(retain?.day1RetainRate) }}</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="metric-card">
            <div class="metric-label">Day7 留存用户</div>
            <div class="metric-value" style="color: #67c23a; font-size:22px">{{ retain?.day7RetainUsers ?? '—' }}</div>
            <div class="metric-sub">{{ formatPercent(retain?.day7RetainRate) }}</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="metric-card">
            <div class="metric-label">Day30 留存用户</div>
            <div class="metric-value" style="color: #909399; font-size:22px">{{ retain?.day30RetainUsers ?? '—' }}</div>
            <div class="metric-sub">{{ formatPercent(retain?.day30RetainRate) }}</div>
          </div>
        </el-col>
      </el-row>
    </div>
    <el-divider style="margin: 12px 0" />
    <div class="shop-info" v-if="shop">
      <span class="shop-name">{{ shop.shopName || '—' }}</span>
      <span class="shop-label">店铺</span>
      <div class="shop-stats">
        <span>GMV: {{ formatMoney(shop.gmv) }}</span>
        <span>订单: {{ shop.orderCount ?? 0 }}</span>
        <span>商品: {{ shop.productCount ?? 0 }}</span>
      </div>
    </div>
    <el-empty v-else-if="!active && !retain && !shop" description="暂无数据" />
  </el-card>
</template>

<script setup>
import { formatMoney, formatPercent } from '../../api/report'

defineProps({
  active: { type: Object, default: null },
  retain: { type: Object, default: null },
  shop: { type: Object, default: null }
})
</script>

<style scoped>
.card-title { font-size: 15px; font-weight: 600; }
.metric-card { text-align: center; padding: 8px 0; }
.metric-label { font-size: 13px; color: #909399; margin-bottom: 6px; }
.metric-value { font-size: 28px; font-weight: 700; }
.shop-info { text-align: center; }
.shop-name { font-size: 18px; font-weight: 600; color: #303133; margin-right: 8px; }
.shop-label { font-size: 12px; color: #fff; background: var(--ds-navy, #1a1a2e); padding: 2px 8px; border-radius: 4px; }
.retain-section { margin-bottom: 8px; }
.retain-header { font-size: 13px; color: #909399; text-align: center; }
.metric-sub { font-size: 12px; color: #c0c4cc; margin-top: 2px; }
.shop-stats { display: flex; justify-content: center; gap: 24px; margin-top: 12px; font-size: 13px; color: #606266; }
</style>
