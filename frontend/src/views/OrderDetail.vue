<template>
  <div class="page-container fade-in" v-loading="loading">
    <div v-if="order" class="order-detail">
      <!-- 状态栏 -->
      <div class="status-bar card" :class="'status-' + order.status">
        <div class="status-icon-wrap">
          <el-icon :size="48" color="#fff"><component :is="statusIcon" /></el-icon>
        </div>
        <div class="status-text">
          <div class="status-title">{{ order.statusText }}</div>
          <div class="status-sub">订单号：{{ order.orderNo }}</div>
        </div>
        <el-button class="status-back" @click="$router.push('/orders')">返回列表</el-button>
      </div>

      <!-- 倒计时栏（仅未支付订单） -->
      <div v-if="order.status === 0 && remainingTime" class="countdown-bar card">
        <el-icon :size="20" color="#e6a23c"><Timer /></el-icon>
        <span class="countdown-text">支付剩余时间：<strong>{{ remainingTime }}</strong></span>
        <span class="countdown-hint">逾期订单将自动取消</span>
      </div>

      <!-- 操作按钮 -->
      <div class="card ops-card">
        <span class="text-muted">下单时间：{{ order.createTime }}</span>
        <div class="ops-btns">
          <el-button v-if="order.status === 0" type="danger" plain @click="handleCancel">取消订单</el-button>
          <el-button v-if="order.status === 0" type="success" plain @click="handleMockPay">模拟支付</el-button>
          <el-button v-if="order.status === 2" type="success" @click="handleReceive">确认收货</el-button>
        </div>
      </div>

      <!-- 收货信息 -->
      <div class="card">
        <h4 class="card-title"><el-icon><Location /></el-icon> 收货信息</h4>
        <div class="addr-info">
          <p>收货人：<strong>{{ order.receiverName }}</strong> {{ order.receiverPhone }}</p>
          <p>收货地址：{{ order.receiverAddress }}</p>
          <p v-if="order.remark">备注：{{ order.remark }}</p>
        </div>
      </div>

      <!-- 商品列表 -->
      <div class="card">
        <h4 class="card-title"><el-icon><Goods /></el-icon> 商品信息</h4>
        <div v-for="item in order.items" :key="item.skuId" class="detail-item">
          <el-image :src="item.productImage || '/placeholder.png'" class="detail-item-img" fit="cover" />
          <div class="detail-item-info">
            <div class="detail-item-name">{{ item.productName }}</div>
            <div class="detail-item-sku">{{ item.skuName }}</div>
          </div>
          <div class="detail-item-price">¥{{ item.price }}</div>
          <div class="detail-item-qty">× {{ item.quantity }}</div>
          <div class="detail-item-total">¥{{ item.subtotal }}</div>
        </div>
      </div>

      <!-- 金额 -->
      <div class="card amount-card">
        <div class="amount-row"><span>商品金额</span><span>¥{{ order.totalAmount }}</span></div>
        <div class="amount-row"><span>运费</span><span>¥{{ order.freight || '0.00' }}</span></div>
        <div class="amount-row"><span>优惠</span><span class="text-success">-¥{{ order.discountAmount || '0.00' }}</span></div>
        <div class="amount-row amount-final"><span>实付</span><span class="text-danger final-price">¥{{ order.actualAmount }}</span></div>
      </div>
    </div>
    <div v-else-if="!loading" class="empty-wrap">
      <el-empty description="订单不存在或已过期" class="empty-state" />
      <el-button type="primary" class="empty-btn" @click="$router.push('/orders')">查看全部订单</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Warning, CircleCheck, InfoFilled, SuccessFilled, CircleClose, Timer, Location, Goods } from '@element-plus/icons-vue'
import { orderApi } from '@/api/order'

const route = useRoute()
const router = useRouter()
const order = ref(null)
const loading = ref(true)
const remainingTime = ref('')
let timerInterval = null

const statusIcon = computed(() => {
  return order.value?.status === 0 ? 'Warning'
    : order.value?.status === 1 ? 'CircleCheck'
    : order.value?.status === 2 ? 'InfoFilled'
    : order.value?.status === 3 ? 'SuccessFilled'
    : order.value?.status === 4 ? 'SuccessFilled'
    : order.value?.status === 5 ? 'CircleClose'
    : 'InfoFilled'
})

function startCountdown() {
  if (order.value?.status !== 0) return
  const createTime = order.value.createTime
  if (!createTime) return
  const createMs = new Date(createTime).getTime()
  const expireMs = createMs + 30 * 60 * 1000

  function updateTime() {
    const now = Date.now()
    const diff = expireMs - now
    if (diff <= 0) {
      remainingTime.value = '已超时'
      clearInterval(timerInterval)
      timerInterval = null
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
    const res = await orderApi.detail(route.params.id)
    order.value = res.data
    if (order.value) {
      startCountdown()
    }
  } catch (e) {
    order.value = null
  } finally {
    loading.value = false
  }
}

async function handleCancel() {
  try {
    await ElMessageBox.confirm('确定取消订单吗？')
    await orderApi.cancel(order.value.id)
    ElMessage.success('订单已取消')
    loadOrder()
  } catch (e) { /* 用户取消操作 */ }
}

async function handlePay() {
  ElMessage.info('正在支付...')
  try {
    await orderApi.mockPay(order.value.orderNo)
    ElMessage.success('支付成功')
    loadOrder()
  } catch (e) {
    ElMessage.error('支付失败')
  }
}

async function handleMockPay() {
  ElMessage.info('正在模拟支付...')
  try {
    await orderApi.mockPay(order.value.orderNo)
    ElMessage.success('模拟支付成功')
    loadOrder()
  } catch (e) {
    ElMessage.error('模拟支付失败')
  }
}

async function handleReceive() {
  try {
    await ElMessageBox.confirm('确认已收到商品吗？')
    await orderApi.receive(order.value.id)
    ElMessage.success('已确认收货')
    loadOrder()
  } catch (e) { /* 用户取消操作 */ }
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
/* 状态栏 ———— 品牌渐变色 + 圆角 */
.status-bar {
  display: flex; align-items: center; gap: 20px; padding: 24px;
  border-radius: var(--ds-radius-lg, 16px); color: #fff;
}
.status-0 { background: var(--ds-coral-gradient, linear-gradient(135deg, #e94560, #ff6b81)); }
.status-1 { background: linear-gradient(135deg, #1a1a2e, #2d2d5e); }
.status-2 { background: linear-gradient(135deg, #1a1a2e, #3a3a6a); }
.status-3 { background: linear-gradient(135deg, #67c23a, #529b2e); }
.status-4 { background: linear-gradient(135deg, #909399, #73767a); }
.status-5 { background: linear-gradient(135deg, #c0c4cc, #909399); }
.status-icon-wrap { flex-shrink: 0; }
.status-text { flex: 1; }
.status-title { font-size: 22px; font-weight: 700; }
.status-sub { font-size: 13px; opacity: 0.85; margin-top: 4px; font-family: monospace; }
.status-back { background: rgba(255,255,255,0.2) !important; border-color: rgba(255,255,255,0.3) !important; color: #fff !important; }
.status-back:hover { background: rgba(255,255,255,0.3) !important; }

.ops-card { display: flex; justify-content: space-between; align-items: center; }
.ops-btns { display: flex; gap: 8px; }

/* 倒计时栏 */
.countdown-bar { display: flex; align-items: center; gap: 8px; padding: 14px 24px; background: #fffbe6; border: 1px solid #ffe58f; border-radius: var(--ds-radius-lg, 16px); }
.countdown-text { font-size: 15px; color: #ad6800; }
.countdown-text strong { font-size: 18px; color: #d46b08; font-family: monospace; }
.countdown-hint { font-size: 12px; color: #999; margin-left: auto; }

.card-title { font-size: 16px; font-weight: 700; color: var(--ds-navy, #1a1a2e); margin-bottom: 16px; display: flex; align-items: center; gap: 8px; }
.addr-info p { font-size: 14px; line-height: 1.8; color: #555; }

/* 商品列表 ———— 圆角图片 + 珊瑚红价格 */
.detail-item { display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--ds-border-light, #f0eee9); }
.detail-item:last-child { border-bottom: none; }
.detail-item-img { width: 56px; height: 56px; border-radius: var(--ds-radius, 10px); flex-shrink: 0; }
.detail-item-info { flex: 1; }
.detail-item-name { font-size: 14px; font-weight: 600; color: var(--ds-navy, #1a1a2e); }
.detail-item-sku { font-size: 12px; color: #999; margin-top: 2px; }
.detail-item-price { width: 70px; text-align: right; color: var(--ds-coral, #e94560); font-weight: 600; }
.detail-item-qty { width: 50px; text-align: right; color: #666; }
.detail-item-total { width: 90px; text-align: right; color: var(--ds-coral, #e94560); font-weight: 700; }

/* 金额卡片 ———— 品牌暖色 */
.amount-card { text-align: right; }
.amount-row { display: flex; justify-content: space-between; font-size: 14px; color: #555; margin-bottom: 8px; }
.amount-final { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--ds-border-light, #f0eee9); font-size: 16px; }
.final-price { font-size: 24px; font-weight: 700; color: var(--ds-coral, #e94560); }

/* 所有卡片统一圆角 */
.status-bar, .ops-card, .card:not(.status-bar):not(.ops-card) {
  border-radius: var(--ds-radius-lg, 16px);
}

.empty-state { margin-top: 40px; }
.empty-wrap { text-align: center; }
.empty-btn { margin-top: 16px; }
</style>
