<template>
  <div class="page-container fade-in">
    <h2 class="page-title">📋 我的订单</h2>

    <!-- 订单状态 Tab -->
    <div class="card tabs-card">
      <el-tabs v-model="activeStatus" @tab-change="loadOrders" class="order-tabs">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="待支付" name="0" />
        <el-tab-pane label="已支付" name="1" />
        <el-tab-pane label="已发货" name="2" />
        <el-tab-pane label="已完成" name="4" />
        <el-tab-pane label="已取消" name="5" />
      </el-tabs>
    </div>

    <!-- 订单列表 -->
    <div v-loading="loading">
      <div v-for="order in orderList" :key="order.id" class="order-card card" :class="{ expanded: expandedOrder === order.id }">
        <div class="order-header" @click="toggleExpand(order.id)">
          <div class="order-header-left">
            <svg class="expand-arrow" :class="{ rotated: expandedOrder === order.id }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9 18 15 12 9 6"/>
            </svg>
            <span class="order-no">订单号：{{ order.orderNo }}</span>
          </div>
          <el-tag :type="statusType(order.status)" class="order-status-tag">{{ order.statusText }}</el-tag>
        </div>
        <transition name="order-slide">
          <div v-show="expandedOrder === order.id" class="order-body">
            <div v-for="item in order.items" :key="item.skuId" class="order-item" @click.stop="goDetail(order)">
              <el-image :src="item.productImage || '/placeholder.png'" class="order-item-img" fit="cover" />
              <div class="order-item-info">
                <div class="order-item-name">{{ item.productName }}</div>
                <div class="order-item-sku">{{ item.skuName }}</div>
              </div>
              <div class="order-item-price">¥{{ item.price }}</div>
              <div class="order-item-qty">× {{ item.quantity }}</div>
            </div>
          </div>
        </transition>
        <div class="order-footer" @click="goDetail(order)">
          共 {{ order.items?.length }} 件商品，合计：<span class="order-total">¥{{ order.actualAmount }}</span>
        </div>
      </div>

      <el-empty v-if="!loading && orderList.length === 0" description="暂无订单" class="empty-state" />
    </div>

    <!-- 分页 -->
    <div v-if="total > 0" class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        @current-change="loadOrders"
        background
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { orderApi } from '@/api/order'

const router = useRouter()
const activeStatus = ref('')
const orderList = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const expandedOrder = ref(null)

function toggleExpand(id) {
  expandedOrder.value = expandedOrder.value === id ? null : id
}

const statusType = (s) => {
  const map = { 0: 'danger', 1: 'success', 2: 'primary', 3: 'success', 4: 'info', 5: 'info' }
  return map[s] || 'info'
}

async function loadOrders() {
  loading.value = true
  try {
    const res = await orderApi.myOrders({
      page: page.value,
      pageSize: pageSize.value,
      status: activeStatus.value || undefined
    })
    orderList.value = res.data?.list || []
    total.value = res.data?.total || 0
  } finally { loading.value = false }
}

function goDetail(order) {
  // 使用订单ID跳转，确保传递正确的参数
  router.push('/order/' + order.id)
}

onMounted(loadOrders)
</script>

<style scoped>
.tabs-card { padding: 8px 20px 0; }
.order-tabs { --el-tabs-header-height: 48px; }
.order-tabs :deep(.el-tabs__item) { font-size: 14px; }

/* 订单卡片 ———— 圆角卡片 + 折叠展开 */
.order-card {
  background: var(--ds-surface, #fff);
  border: 1px solid var(--ds-border-light, #f0eee9);
  border-radius: var(--ds-radius-lg, 16px);
  margin-bottom: 12px;
  overflow: hidden;
  transition: var(--ds-transition);
}
.order-card:hover {
  box-shadow: var(--ds-shadow-md);
  transform: translateY(-2px);
}
.order-card.expanded {
  box-shadow: var(--ds-shadow-lg);
  border-color: var(--ds-coral, #e94560);
}

/* 展开箭头 */
.order-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px;
  cursor: pointer;
  user-select: none;
  transition: background 0.2s;
}
.order-header:hover { background: rgba(0,0,0,0.02); }
.order-header-left { display: flex; align-items: center; gap: 10px; }
.expand-arrow {
  width: 18px; height: 18px;
  color: var(--ds-coral, #e94560);
  transition: transform 0.3s ease;
  flex-shrink: 0;
}
.expand-arrow.rotated { transform: rotate(90deg); }
.order-no { font-size: 13px; color: #999; font-family: monospace; }
.order-status-tag { font-size: 12px; padding: 4px 12px; flex-shrink: 0; }

/* 展开内容 */
.order-body { padding: 8px 20px; }
.order-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 0; border-bottom: 1px solid #f8f8f8;
  cursor: pointer;
  border-radius: var(--ds-radius-sm, 6px);
  transition: background 0.2s;
}
.order-item:last-child { border-bottom: none; }
.order-item:hover { background: rgba(233,69,96,0.04); }
.order-item-img { width: 56px; height: 56px; border-radius: 10px; flex-shrink: 0; }
.order-item-info { flex: 1; }
.order-item-name { font-size: 14px; font-weight: 600; color: var(--ds-navy, #1a1a2e); }
.order-item-sku { font-size: 12px; color: #999; margin-top: 2px; }
.order-item-price { width: 70px; text-align: right; color: var(--ds-coral, #e94560); font-weight: 600; }
.order-item-qty { width: 50px; text-align: right; color: #666; }

.order-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--ds-border-light, #f0eee9);
  text-align: right;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  transition: background 0.2s;
}
.order-footer:hover { background: var(--ds-bg, #f5f3ef); }
.order-total { font-size: 18px; font-weight: 700; color: var(--ds-coral, #e94560); margin-left: 4px; }

/* slide 折叠动画 */
.order-slide-enter-active { transition: all 0.3s ease; }
.order-slide-leave-active { transition: all 0.25s ease; }
.order-slide-enter-from { opacity: 0; max-height: 0; }
.order-slide-leave-to { opacity: 0; max-height: 0; }
.order-slide-enter-to { opacity: 1; max-height: 300px; }
.order-slide-leave-from { opacity: 1; max-height: 300px; }

.empty-state { margin-top: 40px; }
.pagination-wrap { display: flex; justify-content: center; margin-top: 24px; }
</style>
