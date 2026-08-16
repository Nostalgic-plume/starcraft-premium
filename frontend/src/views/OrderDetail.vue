<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 + 面包屑 -->
    <BackBar label="返回订单列表" to="/orders">
      <n-breadcrumb class="od-breadcrumb">
        <n-breadcrumb-item @click="router.push('/home')">首页</n-breadcrumb-item>
        <n-breadcrumb-item @click="router.push('/orders')">我的订单</n-breadcrumb-item>
        <n-breadcrumb-item>订单详情</n-breadcrumb-item>
      </n-breadcrumb>
    </BackBar>

    <n-spin :show="loading">
      <template v-if="order">
        <!-- 状态卡片：状态大字 + 倒计时 + 操作 -->
        <div class="status-card" :style="{ background: statusBg }">
          <div class="status-badge">
            <SvgIcon :name="statusIcon" :size="26" />
          </div>
          <div class="status-main">
            <div class="status-title">{{ order.statusText }}</div>
            <div class="status-sub">订单号：{{ order.orderNo }}</div>
            <div v-if="order.status === 0 && remainingTime" class="countdown">
              <SvgIcon name="clock" :size="14" />
              支付剩余时间 <strong>{{ remainingTime }}</strong>，逾期订单将自动取消
            </div>
          </div>
          <div class="status-ops">
            <n-button v-if="order.status === 0" quaternary class="ops-btn ghost" @click="handleCancel">
              取消订单
            </n-button>
            <n-button v-if="order.status === 0" type="primary" size="large" class="ops-btn" @click="handlePay">
              去支付
            </n-button>
            <n-button v-if="order.status === 2" type="primary" size="large" class="ops-btn" @click="handleReceive">
              确认收货
            </n-button>
          </div>
        </div>

        <!-- 订单进度：下单 → 支付 → 发货 → 收货 → 完成 -->
        <div class="steps-card">
          <n-steps size="small">
            <n-step
              v-for="(step, i) in orderSteps"
              :key="step.title"
              :title="step.title"
              :description="step.description"
              :status="stepState(i)"
            />
          </n-steps>
        </div>

        <!-- 收货信息 -->
        <div class="od-card">
          <div class="card-head">
            <SvgIcon name="location" :size="18" />
            <h3 class="card-title">收货信息</h3>
          </div>
          <div class="receiver-info">
            <p class="receiver-line">
              <strong>{{ order.receiverName }}</strong>
              <span class="receiver-phone">{{ order.receiverPhone }}</span>
            </p>
            <p class="receiver-addr">{{ order.receiverAddress }}</p>
            <p v-if="order.remark" class="receiver-remark">备注：{{ order.remark }}</p>
          </div>
        </div>

        <!-- 商品列表 -->
        <div class="od-card">
          <div class="card-head">
            <SvgIcon name="box" :size="18" />
            <h3 class="card-title">商品信息</h3>
            <span class="card-sub">共 {{ order.items.length }} 件</span>
          </div>
          <div class="goods-list">
            <div v-for="item in order.items" :key="item.skuId" class="goods-row">
              <div class="goods-thumb">
                <img
                  v-if="item.productImage"
                  :src="item.productImage"
                  alt=""
                  loading="lazy"
                />
                <SvgIcon v-else name="box" :size="22" class="thumb-icon" />
              </div>
              <div class="goods-info">
                <p class="goods-name">{{ item.productName }}</p>
                <p class="goods-sku">{{ item.skuName }}</p>
              </div>
              <div class="goods-qty">× {{ item.quantity }}</div>
              <div class="goods-price">
                <span class="price-symbol">¥</span>{{ formatPrice(item.price) }}
              </div>
            </div>
          </div>
        </div>

        <!-- 金额明细 -->
        <div class="od-card amount-card">
          <div class="meta-grid">
            <div class="meta-item">
              <span class="meta-label">订单编号</span>
              <span class="meta-value mono">{{ order.orderNo }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">下单时间</span>
              <span class="meta-value">{{ formatTime(order.createTime) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">支付方式</span>
              <span class="meta-value">{{ isPaid ? '在线支付' : '-' }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">支付时间</span>
              <span class="meta-value">{{ formatTime(order.payTime) }}</span>
            </div>
          </div>

          <div class="amount-rows">
            <div class="amount-row">
              <span>商品金额</span>
              <span>¥{{ formatPrice(order.totalAmount) }}</span>
            </div>
            <div class="amount-row">
              <span>运费</span>
              <span class="sum-free">免运费</span>
            </div>
            <div class="amount-row">
              <span>优惠</span>
              <span class="sum-discount">-¥{{ formatPrice(order.discountAmount) }}</span>
            </div>
            <div class="amount-divider"></div>
            <div class="amount-row amount-final">
              <span>实付</span>
              <span class="payable-value">
                <span class="price-symbol">¥</span>{{ formatPrice(order.actualAmount) }}
              </span>
            </div>
          </div>
        </div>
      </template>

      <!-- 空状态：订单不存在 -->
      <EmptyState
        v-else-if="!loading"
        text="订单不存在或已过期"
        description="请返回订单列表查看其他订单"
        icon="orders"
      >
        <button type="button" class="go-orders-btn" @click="router.push('/orders')">查看全部订单</button>
      </EmptyState>
    </n-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NBreadcrumb, NBreadcrumbItem, NButton, NSpin, NSteps, NStep, useMessage } from 'naive-ui'
import { orderApi } from '@/api/order'
import { useConfirm } from '@/utils/confirm'
import BackBar from '@/components/BackBar.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { OrderVO } from '@/api/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const { confirm } = useConfirm()

const order = ref<OrderVO | null>(null)
const loading = ref(true)
const remainingTime = ref('')
let timerInterval: ReturnType<typeof setInterval> | null = null

/** 订单进度步骤：下单 → 支付 → 发货 → 收货 → 完成 */
const orderSteps = [
  { title: '下单', description: '订单已提交' },
  { title: '支付', description: '完成在线支付' },
  { title: '发货', description: '商家已发货' },
  { title: '收货', description: '确认收货' },
  { title: '完成', description: '交易完成' }
]

/** 是否处于正常流转（非取消/退款） */
const isInFlow = computed(() => {
  const s = order.value?.status
  return s !== undefined && s !== 5 && s !== 6
})

/** 状态语义色（同 StatusTag）：蓝 / 橙 / 绿 / 灰 */
const STATUS_COLOR: Record<number, string> = {
  0: '#E08600',
  1: '#2D7FF9',
  2: '#2D7FF9',
  3: '#12A77D',
  4: '#12A77D',
  5: '#98A1AF',
  6: '#98A1AF'
}

const statusIcon = computed(() => {
  const s = order.value?.status ?? -1
  if (s === 0) return 'clock'
  if (s === 1) return 'check-circle'
  if (s === 2) return 'box'
  if (s === 3) return 'receive'
  if (s === 4) return 'check-circle'
  if (s === 5 || s === 6) return 'cancel'
  return 'box'
})

const statusColor = computed(() => STATUS_COLOR[order.value?.status ?? -1] || '#2D7FF9')

const statusBg = computed(
  () => `linear-gradient(135deg, ${statusColor.value}1A, ${statusColor.value}0A)`
)

const isPaid = computed(() => {
  const s = order.value?.status ?? -1
  return s >= 1 && s !== 5 && s !== 6
})

/** 步骤状态：取消/退款 → 全部等待；正常流转按当前阶段高亮 */
function stepState(i: number): 'finish' | 'process' | 'wait' {
  if (!isInFlow.value) return 'wait'
  const s = order.value!.status
  const cur = s >= 4 ? 5 : s + 1
  if (i < cur) return 'finish'
  if (i === cur) return 'process'
  return 'wait'
}

function formatPrice(val: number | null | undefined): string {
  const n = Number(val || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/** 时间展示：ISO → YYYY-MM-DD HH:mm */
function formatTime(t: string | null | undefined): string {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

/** 待支付订单：30 分钟支付倒计时 */
function startCountdown() {
  if (order.value?.status !== 0) return
  const createTime = order.value.createTime
  if (!createTime) return
  const createMs = new Date(createTime).getTime()
  const expireMs = createMs + 30 * 60 * 1000

  function updateTime() {
    const diff = expireMs - Date.now()
    if (diff <= 0) {
      remainingTime.value = ''
      if (timerInterval) {
        clearInterval(timerInterval)
        timerInterval = null
      }
      return
    }
    const minutes = Math.floor(diff / 60000)
    const seconds = Math.floor((diff % 60000) / 1000)
    remainingTime.value = `${minutes}分${seconds}秒`
  }

  updateTime()
  timerInterval = setInterval(updateTime, 1000)
}

async function loadOrder() {
  loading.value = true
  try {
    const res = await orderApi.detail(route.params.id as string)
    order.value = res.data
    if (order.value) startCountdown()
  } catch {
    order.value = null
  } finally {
    loading.value = false
  }
}

async function handleCancel() {
  const ok = await confirm({ content: '确定取消该订单吗？' })
  if (!ok) return
  try {
    await orderApi.cancel(order.value!.id)
    message.success('订单已取消')
    loadOrder()
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

/** 去支付：调用模拟支付（后端 /api/pay/mock-pay，传 orderNo） */
async function handlePay() {
  try {
    await orderApi.mockPay(order.value!.orderNo)
    message.success('支付成功')
    loadOrder()
  } catch {
    message.error('支付失败，请重试')
  }
}

async function handleReceive() {
  const ok = await confirm({ content: '确认已收到商品吗？' })
  if (!ok) return
  try {
    await orderApi.receive(order.value!.id)
    message.success('已确认收货')
    loadOrder()
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

onMounted(loadOrder)

onUnmounted(() => {
  if (timerInterval) {
    clearInterval(timerInterval)
    timerInterval = null
  }
})
</script>

<style scoped>
/* ====== 面包屑 ====== */
.od-breadcrumb {
  margin-left: 4px;
}

/* ====== 状态卡片 ====== */
.status-card {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 24px 26px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  background: var(--ds-card, #fff);
  margin-bottom: 16px;
}
.status-badge {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  background: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
}
.status-main {
  flex: 1;
  min-width: 0;
}
.status-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
}
.status-sub {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 4px;
  font-family: var(--ds-mono, Consolas, monospace);
}
.countdown {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding: 5px 12px;
  border-radius: var(--ds-r-pill, 999px);
  background: rgba(224, 134, 0, 0.1);
  color: #b45f00;
  font-size: 12px;
}
.countdown strong {
  font-size: 14px;
  font-family: var(--ds-mono, Consolas, monospace);
  color: #e08600;
}
.status-ops {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.ops-btn {
  min-width: 96px;
  border-radius: var(--ds-r, 12px);
  font-weight: 600;
}
.ops-btn.ghost {
  color: var(--ds-text-2, #5a6474);
}

/* ====== 订单进度 ====== */
.steps-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 22px 26px;
  margin-bottom: 16px;
}

/* ====== 通用卡片 ====== */
.od-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 20px 24px;
  margin-bottom: 16px;
  transition: all 0.25s ease;
}
.od-card:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  color: var(--ds-primary, #2d7ff9);
}
.card-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 0;
}
.card-sub {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-left: auto;
}

/* ====== 收货信息 ====== */
.receiver-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 8px;
}
.receiver-line strong {
  font-size: 15px;
  color: var(--ds-text, #1e2632);
}
.receiver-phone {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  font-variant-numeric: tabular-nums;
}
.receiver-addr {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  line-height: 1.6;
  margin: 0 0 8px;
}
.receiver-remark {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}

/* ====== 商品列表 ====== */
.goods-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.goods-row:last-child {
  border-bottom: none;
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
.thumb-icon {
  color: var(--ds-primary, #2d7ff9);
  opacity: 0.5;
}
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
  margin: 0 0 4px;
}
.goods-sku {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}
.goods-qty {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
  white-space: nowrap;
}
.goods-price {
  width: 96px;
  text-align: right;
  color: var(--ds-price, #2d7ff9);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.price-symbol {
  font-size: 11px;
  font-weight: 700;
}

/* ====== 金额明细 ====== */
.meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  padding-bottom: 16px;
  margin-bottom: 16px;
  border-bottom: 1px dashed var(--ds-line, #e7e9ee);
}
.meta-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.meta-label {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  flex-shrink: 0;
}
.meta-value {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  word-break: break-all;
}
.mono {
  font-family: var(--ds-mono, Consolas, monospace);
}
.amount-rows {
  text-align: right;
}
.amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: var(--ds-text-2, #5a6474);
  padding: 6px 0;
}
.amount-row span:last-child {
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  font-variant-numeric: tabular-nums;
}
.sum-free {
  color: var(--ds-green, #12a77d) !important;
}
.sum-discount {
  color: var(--ds-red, #e0404e) !important;
}
.amount-divider {
  border-top: 1px solid var(--ds-line, #e7e9ee);
  margin: 8px 0;
}
.amount-final {
  font-size: 15px;
  color: var(--ds-text, #1e2632);
}
.payable-value {
  font-size: 28px;
  font-weight: 800;
  color: var(--ds-price, #2d7ff9);
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.payable-value .price-symbol {
  font-size: 15px;
}

/* ====== 空状态按钮 ====== */
.go-orders-btn {
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
.go-orders-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

@media (max-width: 560px) {
  .status-card {
    flex-direction: column;
    align-items: flex-start;
  }
  .status-ops {
    width: 100%;
    justify-content: flex-end;
  }
  .meta-grid {
    grid-template-columns: 1fr;
  }
}
</style>
