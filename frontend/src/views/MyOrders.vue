<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <h2 class="mo-title">我的订单</h2>

    <!-- 状态筛选 tabs -->
    <div class="tabs-card">
      <n-tabs
        v-model:value="activeStatus"
        type="line"
        class="order-tabs"
        @update:value="onTabChange"
      >
        <n-tab v-for="t in tabOptions" :key="t.value" :name="t.value">
          {{ t.label }}
        </n-tab>
      </n-tabs>
    </div>

    <!-- 订单列表 -->
    <n-spin :show="loading">
      <div v-if="orderList.length" class="order-list">
        <OrderCard
          v-for="order in orderList"
          :key="order.id"
          :order="order"
          @pay="onPay"
          @cancel="onCancel"
          @receive="onReceive"
          @remind="onRemind"
          @rebuy="onRebuy"
          @review="onReview"
          @view="onView"
        />
      </div>

      <EmptyState
        v-else-if="!loading"
        text="暂无订单"
        description="去挑选心仪的商品吧"
        icon="orders"
      >
        <button type="button" class="go-shop-btn" @click="router.push('/home')">去逛逛</button>
      </EmptyState>
    </n-spin>

    <!-- 分页 -->
    <div v-if="total > 0" class="pagination-wrap">
      <n-pagination
        v-model:page="page"
        :page-size="pageSize"
        :item-count="total"
        :page-slot="5"
        @update:page="loadOrders"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NTab, NTabs, NPagination, NSpin, useMessage } from 'naive-ui'
import { orderApi } from '@/api/order'
import { cartApi } from '@/api/cart'
import { useConfirm } from '@/utils/confirm'
import BackBar from '@/components/BackBar.vue'
import OrderCard from '@/components/OrderCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { OrderVO } from '@/api/types'

const router = useRouter()
const message = useMessage()
const { confirm } = useConfirm()

/** 状态 tabs：value 为后端 status 参数（'' 表示全部） */
const tabOptions = [
  { label: '全部', value: '' },
  { label: '待付款', value: '0' },
  { label: '待发货', value: '1' },
  { label: '待收货', value: '2' },
  { label: '已完成', value: '4' }
]

const activeStatus = ref('')
const orderList = ref<OrderVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

function onTabChange() {
  page.value = 1
  loadOrders()
}

async function loadOrders() {
  loading.value = true
  try {
    const res = await orderApi.myOrders({
      page: page.value,
      pageSize: pageSize.value,
      status: activeStatus.value === '' ? undefined : activeStatus.value
    })
    orderList.value = res.data?.list || []
    total.value = Number(res.data?.total) || 0
  } finally {
    loading.value = false
  }
}

/* ====== 订单操作 ====== */

/** 待付款 → 去支付：跳转订单详情（详情页含模拟支付与倒计时） */
function onPay(order: OrderVO) {
  router.push('/order/' + order.id)
}

/** 待付款 → 取消订单 */
async function onCancel(order: OrderVO) {
  const ok = await confirm({ content: '确定取消该订单吗？' })
  if (!ok) return
  try {
    await orderApi.cancel(order.id)
    message.success('订单已取消')
    loadOrders()
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

/** 待收货（已发货）→ 确认收货 */
async function onReceive(order: OrderVO) {
  const ok = await confirm({ content: '确认已收到商品吗？' })
  if (!ok) return
  try {
    await orderApi.receive(order.id)
    message.success('已确认收货')
    loadOrders()
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

/** 待发货 → 提醒发货（占位：后端无该接口） */
function onRemind(_order: OrderVO) {
  // TODO(P0): 后端未提供"提醒发货"接口，占位提示
  message.success('已提醒商家尽快发货')
}

/** 已完成 → 再次购买：按订单快照 skuId 重新加入购物车 */
async function onRebuy(order: OrderVO) {
  const items = order.items || []
  if (items.length === 0) {
    message.warning('订单无商品可重新购买')
    return
  }
  try {
    for (const item of items) {
      await cartApi.add({ skuId: item.skuId, quantity: item.quantity })
    }
    message.success('已重新加入购物车')
    router.push('/cart')
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

/** 已完成 → 评价（占位：评价功能后端未就绪） */
function onReview(_order: OrderVO) {
  // TODO(P0): 评价模块后端未就绪，占位提示
  message.info('评价功能开发中，敬请期待')
}

/** 查看详情 */
function onView(order: OrderVO) {
  router.push('/order/' + order.id)
}

onMounted(loadOrders)
</script>

<style scoped>
.mo-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0 0 20px;
}

/* 状态 tabs */
.tabs-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 6px 20px 0;
  margin-bottom: 16px;
}
.order-tabs {
  --n-tabs-bar-color: var(--ds-primary, #2d7ff9);
}
.order-tabs :deep(.n-tabs-nav__segment) {
  justify-content: flex-start;
}
.order-tabs :deep(.n-tab) {
  font-size: 14px;
  color: var(--ds-text-2, #5a6474);
  transition: color 0.2s ease;
}
.order-tabs :deep(.n-tab:hover) {
  color: var(--ds-primary, #2d7ff9);
}
.order-tabs :deep(.n-tab--active) {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 700;
}

/* 订单列表 */
.order-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 分页 */
.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}

/* 空状态按钮 */
.go-shop-btn {
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
.go-shop-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}
</style>
