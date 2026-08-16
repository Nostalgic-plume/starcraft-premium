<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { OrderVO } from '@/api/types'
import StatusTag, { type StatusType } from './StatusTag.vue'
import SvgIcon from './SvgIcon.vue'

const props = defineProps<{
  /** 订单数据 */
  order: OrderVO
}>()

const emit = defineEmits<{
  (e: 'pay', order: OrderVO): void
  (e: 'cancel', order: OrderVO): void
  (e: 'receive', order: OrderVO): void
  (e: 'view', order: OrderVO): void
  (e: 'remind', order: OrderVO): void
  (e: 'rebuy', order: OrderVO): void
  (e: 'review', order: OrderVO): void
}>()

const router = useRouter()

/** 订单状态（后端）：0待支付 1已支付 2已发货 3已收货 4已完成 5已取消 6已退款 → StatusTag 语义类型 */
const statusTypeMap: Record<number, StatusType> = {
  0: 'pending-pay',
  1: 'pending-ship',
  2: 'paid',
  3: 'completed',
  4: 'completed',
  5: 'cancelled',
  6: 'cancelled'
}

const statusType = computed<StatusType>(() => statusTypeMap[props.order.status] || 'paid')
const itemCount = computed(() => props.order.items?.length || 0)
const firstItem = computed(() => props.order.items?.[0])

/** 时间展示：ISO → YYYY-MM-DD HH:mm */
function formatTime(t: string | null | undefined): string {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

function emitAction(e: 'pay' | 'cancel' | 'receive' | 'view' | 'remind' | 'rebuy' | 'review', order: OrderVO) {
  switch (e) {
    case 'pay': emit('pay', order); break
    case 'cancel': emit('cancel', order); break
    case 'receive': emit('receive', order); break
    case 'remind': emit('remind', order); break
    case 'rebuy': emit('rebuy', order); break
    case 'review': emit('review', order); break
    default: emit('view', order)
  }
}

function goDetail() {
  emit('view', props.order)
  router.push('/order/' + props.order.id)
}
</script>

<template>
  <div class="order-card">
    <!-- 头部：单号 + 时间 + 状态 -->
    <div class="order-head">
      <div class="order-head-left">
        <span class="order-no">{{ order.orderNo }}</span>
        <span class="order-time">
          <SvgIcon name="clock" :size="13" />
          {{ formatTime(order.createTime) }}
        </span>
      </div>
      <StatusTag :type="statusType" :label="order.statusText" />
    </div>

    <!-- 商品预览 -->
    <div class="order-body" @click="goDetail">
      <div class="item-preview">
        <div v-if="firstItem" class="item-thumb">
          <img
            v-if="firstItem.productImage"
            :src="firstItem.productImage"
            class="thumb-img"
            alt=""
            loading="lazy"
          />
          <SvgIcon v-else name="image" :size="22" class="thumb-icon" />
        </div>
        <div v-else class="item-thumb">
          <SvgIcon name="box" :size="22" class="thumb-icon" />
        </div>
        <div class="item-summary">
          <p v-if="firstItem" class="item-name">{{ firstItem.productName }}</p>
          <p class="item-count">共 {{ itemCount }} 件商品</p>
        </div>
      </div>
    </div>

    <!-- 底部：金额 + 操作 -->
    <div class="order-foot">
      <div class="amount">
        <span class="amount-label">实付</span>
        <span class="amount-value">¥{{ order.actualAmount }}</span>
      </div>
      <div class="ops">
        <button
          v-if="order.status === 0"
          class="op-btn"
          type="button"
          @click="emitAction('cancel', order)"
        >取消订单</button>
        <button
          v-if="order.status === 0"
          class="op-btn op-btn-primary"
          type="button"
          @click="emitAction('pay', order)"
        >去支付</button>
        <button
          v-if="order.status === 2"
          class="op-btn op-btn-primary"
          type="button"
          @click="emitAction('receive', order)"
        >确认收货</button>
        <button
          v-if="order.status === 1"
          class="op-btn op-btn-primary"
          type="button"
          @click="emitAction('remind', order)"
        >提醒发货</button>
        <button
          v-if="order.status === 4"
          class="op-btn"
          type="button"
          @click="emitAction('rebuy', order)"
        >再次购买</button>
        <button
          v-if="order.status === 4"
          class="op-btn"
          type="button"
          @click="emitAction('review', order)"
        >评价</button>
        <button
          class="op-btn"
          type="button"
          @click="goDetail"
        >查看详情</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.order-card {
  background: var(--ds-card, #ffffff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  overflow: hidden;
  transition: all 0.25s ease;
}
.order-card:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.order-head-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.order-no {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  font-family: var(--ds-mono, Consolas, monospace);
}
.order-time {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

.order-body {
  padding: 14px 18px;
  cursor: pointer;
  transition: background 0.2s ease;
}
.order-body:hover {
  background: var(--ds-bg, #f6f7fa);
}
.item-preview {
  display: flex;
  align-items: center;
  gap: 12px;
}
.item-thumb {
  width: 52px;
  height: 52px;
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
  flex-shrink: 0;
  background: var(--ds-bg-2, #eef0f4);
  display: flex;
  align-items: center;
  justify-content: center;
}
.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumb-icon {
  color: var(--ds-text-3, #98a1af);
}
.item-summary {
  min-width: 0;
}
.item-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 0 0 4px;
}
.item-count {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}

.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-top: 1px solid var(--ds-line, #e7e9ee);
}
.amount {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.amount-label {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
}
.amount-value {
  font-size: 20px;
  font-weight: 800;
  color: var(--ds-price, #2d7ff9);
  letter-spacing: -0.5px;
}
.ops {
  display: flex;
  gap: 8px;
}
.op-btn {
  padding: 7px 16px;
  border-radius: var(--ds-r, 12px);
  border: 1px solid var(--ds-line-2, #d8dce4);
  background: transparent;
  color: var(--ds-text-2, #5a6474);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}
.op-btn:hover {
  color: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.op-btn-primary {
  background: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
  color: #fff;
}
.op-btn-primary:hover {
  background: var(--ds-primary-strong, #1e63d6);
  border-color: var(--ds-primary-strong, #1e63d6);
  color: #fff;
}
</style>
