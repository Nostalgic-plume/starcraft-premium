<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 页头 -->
    <div class="page-head">
      <h2 class="pg-title">我的售后</h2>
      <button type="button" class="apply-btn" @click="router.push('/after-sales/new')">
        <SvgIcon name="shield" :size="16" />
        申请售后
      </button>
    </div>

    <!-- 售后列表 -->
    <div v-if="afterSaleList.length" class="as-list">
      <div v-for="a in afterSaleList" :key="a.id" class="as-card">
        <div class="as-head">
          <div class="as-head-left">
            <span class="as-type">{{ a.type }}</span>
            <span class="as-no mono">{{ a.no }}</span>
          </div>
          <span class="as-status" :style="statusStyle(a.status)">
            {{ a.statusText }}
          </span>
        </div>

        <div class="as-body">
          <div class="goods-thumb">
            <img v-if="a.productImage" :src="a.productImage" alt="" loading="lazy" />
            <SvgIcon v-else name="box" :size="22" class="thumb-icon" />
          </div>
          <div class="goods-info">
            <p class="goods-name">{{ a.productName }}</p>
            <p class="as-meta">申请时间：{{ a.createTime }}</p>
          </div>
          <div class="as-amount">
            <span class="amount-label">退款金额</span>
            <span class="amount-value">
              <span class="price-symbol">¥</span>{{ formatPrice(a.refundAmount) }}
            </span>
          </div>
        </div>

        <div v-if="a.reason" class="as-reason">
          原因：{{ a.reason }}
        </div>
      </div>
    </div>

    <EmptyState
      v-else
      text="暂无售后记录"
      description="订单确认收货后可发起退货退款或换货"
      icon="shield"
    >
      <button type="button" class="go-order" @click="router.push('/orders')">查看订单</button>
    </EmptyState>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import BackBar from '@/components/BackBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'

/** 售后单（占位结构，后端就绪后替换为 types.ts 的 AfterSaleVO） */
interface AfterSaleVO {
  id: string
  no: string
  type: string
  /** 0=审核中 1=处理中 2=已完成 3=已拒绝 */
  status: number
  statusText: string
  refundAmount: number
  productName: string
  productImage: string
  reason: string
  createTime: string
}

const router = useRouter()

/** 占位数据：接口未就绪，前端先用静态数据演示（TODO(占位) 接入 GET /api/after-sale/my 后移除） */
const afterSaleList = ref<AfterSaleVO[]>([
  // TODO(占位): 以下为本地占位数据
  { id: 'a1', no: 'AS20260815001', type: '退货退款', status: 1, statusText: '处理中', refundAmount: 299, productName: '星创无线蓝牙耳机 Pro', productImage: '', reason: '音质不满足预期，与描述不符', createTime: '2026-08-15 10:22' },
  { id: 'a2', no: 'AS20260810008', type: '换货', status: 2, statusText: '已完成', refundAmount: 89, productName: '轻奢便携保温杯 500ml', productImage: '', reason: '杯盖有轻微划痕', createTime: '2026-08-10 16:40' }
])

/** 状态语义色（蓝 / 橙 / 绿 / 红） */
function statusStyle(status: number) {
  if (status === 0) return { color: '#E08600', background: 'rgba(224,134,0,0.10)' }
  if (status === 1) return { color: '#2D7FF9', background: 'rgba(45,127,249,0.08)' }
  if (status === 2) return { color: '#12A77D', background: 'rgba(18,167,125,0.10)' }
  return { color: '#E0404E', background: 'rgba(224,64,78,0.10)' }
}

function formatPrice(val: number | null | undefined): string {
  const n = Number(val || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.pg-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0;
}
.apply-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 9px 18px;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.apply-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

/* 售后列表 */
.as-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.as-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 16px 20px;
  transition: all 0.25s ease;
}
.as-card:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.as-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.as-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.as-type {
  font-size: 14px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
}
.as-no {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.mono { font-family: var(--ds-mono, Consolas, monospace); }
.as-status {
  padding: 3px 12px;
  border-radius: var(--ds-r-pill, 999px);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  flex-shrink: 0;
}

.as-body {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 0;
}
.goods-thumb {
  width: 60px;
  height: 60px;
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
  flex-shrink: 0;
  background: var(--ds-bg-2, #eef0f4);
  display: flex;
  align-items: center;
  justify-content: center;
}
.goods-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.thumb-icon { color: var(--ds-primary, #2d7ff9); opacity: 0.5; }
.goods-info {
  flex: 1;
  min-width: 0;
}
.goods-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 0 0 6px;
}
.as-meta {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}
.as-amount {
  text-align: right;
  flex-shrink: 0;
}
.amount-label {
  display: block;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-bottom: 2px;
}
.amount-value {
  color: var(--ds-price, #2d7ff9);
  font-weight: 800;
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.price-symbol {
  font-size: 12px;
  font-weight: 700;
}
.as-reason {
  padding-top: 12px;
  border-top: 1px dashed var(--ds-line, #e7e9ee);
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  line-height: 1.6;
}

/* 空态按钮 */
.go-order {
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 9px 24px;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.go-order:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

@media (max-width: 560px) {
  .as-body { align-items: flex-start; }
  .as-amount { text-align: left; margin-top: 6px; }
}
</style>
