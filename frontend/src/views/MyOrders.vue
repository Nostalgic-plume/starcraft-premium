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
      <div v-for="order in orderList" :key="order.id" class="order-card card" @click="goDetail(order)">
        <div class="order-header">
          <span class="order-no">订单号：{{ order.orderNo }}</span>
          <el-tag :type="statusType(order.status)" class="order-status-tag">{{ order.statusText }}</el-tag>
        </div>
        <div class="order-body">
          <div v-for="item in order.items" :key="item.skuId" class="order-item">
            <el-image :src="item.productImage || '/placeholder.png'" class="order-item-img" fit="cover" />
            <div class="order-item-info">
              <div class="order-item-name">{{ item.productName }}</div>
              <div class="order-item-sku">{{ item.skuName }}</div>
            </div>
            <div class="order-item-price">¥{{ item.price }}</div>
            <div class="order-item-qty">× {{ item.quantity }}</div>
          </div>
        </div>
        <div class="order-footer">
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

const statusType = (s) => {
  const map = { 0: 'danger', 1: 'warning', 2: 'primary', 3: 'success', 4: 'success', 5: 'info' }
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
.order-card { cursor: pointer; transition: all 0.25s; padding: 0; overflow: hidden; }
.order-card:hover { box-shadow: 0 8px 24px rgba(0,0,0,0.1); transform: translateY(-2px); }

.order-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-bottom: 1px solid #f5f5f5;
}
.order-no { font-size: 13px; color: #999; font-family: monospace; }
.order-status-tag { font-size: 12px; padding: 4px 12px; }

.order-body { padding: 8px 20px; }
.order-item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid #f8f8f8; }
.order-item:last-child { border-bottom: none; }
.order-item-img { width: 56px; height: 56px; border-radius: 8px; flex-shrink: 0; }
.order-item-info { flex: 1; }
.order-item-name { font-size: 14px; font-weight: 600; color: #1a1a2e; }
.order-item-sku { font-size: 12px; color: #999; margin-top: 2px; }
.order-item-price { width: 70px; text-align: right; color: var(--danger); }
.order-item-qty { width: 50px; text-align: right; color: #666; }

.order-footer {
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
  text-align: right;
  font-size: 14px;
  color: #666;
}
.order-total { font-size: 18px; font-weight: 700; color: var(--danger); margin-left: 4px; }
.empty-state { margin-top: 40px; }
.pagination-wrap { display: flex; justify-content: center; margin-top: 24px; }
</style>
