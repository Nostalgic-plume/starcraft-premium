<template>
  <div class="page fade-in">
    <!-- 工具条：关键字 + 状态 + 时间范围 -->
    <div class="toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="订单号 / 收货人 / 电话"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <n-select
        v-model:value="statusFilter"
        :options="statusOptions"
        placeholder="订单状态"
        clearable
        style="width: 140px"
        @update:value="handleFilterChange"
      />
      <n-date-picker
        v-model:formatted-value="dateRange"
        type="daterange"
        value-format="yyyy-MM-dd"
        clearable
        style="width: 250px"
      />
      <n-button type="primary" @click="handleSearch">搜索</n-button>
      <n-button quaternary @click="handleReset">重置</n-button>
    </div>

    <!-- 订单表格 -->
    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :scroll-x="980"
      />
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

    <!-- 订单详情抽屉 -->
    <n-drawer v-model:show="showDetail" :width="540" placement="right">
      <n-drawer-content title="订单详情" closable>
        <div v-if="detailLoading" class="drawer-loading">
          <n-spin size="large" />
        </div>

        <template v-else-if="detail">
          <!-- 收货信息 -->
          <div class="detail-section">
            <h4 class="detail-title">收货信息</h4>
            <div class="detail-item">
              <span class="detail-label">收件人</span>
              <span>{{ detail.receiverName || '-' }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">联系电话</span>
              <span>{{ detail.receiverPhone || '-' }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">收货地址</span>
              <span class="detail-value">{{ detail.receiverAddress || '-' }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">物流单号</span>
              <span>{{ detail.logisticsNo || '-' }}</span>
            </div>
          </div>

          <!-- 商品清单 -->
          <div class="detail-section">
            <h4 class="detail-title">商品清单</h4>
            <div v-if="detail.items && detail.items.length" class="item-list">
              <div v-for="(it, idx) in detail.items" :key="idx" class="order-item">
                <img
                  class="item-thumb"
                  :src="it.productImage || IMG_PLACEHOLDER"
                  alt=""
                  @error="onItemImgError"
                />
                <div class="item-info">
                  <div class="item-name">{{ it.productName }}</div>
                  <div v-if="it.skuName" class="item-sku">规格：{{ it.skuName }}</div>
                </div>
                <div class="item-amount">
                  <div class="item-price">{{ money(it.price) }}</div>
                  <div class="item-qty">x{{ it.quantity || 0 }}</div>
                  <div class="item-subtotal cell-price">{{ money(it.subtotal) }}</div>
                </div>
              </div>
            </div>
            <div v-else class="cell-muted">暂无商品明细</div>
          </div>

          <!-- 金额明细 -->
          <div class="detail-section">
            <h4 class="detail-title">金额明细</h4>
            <div class="detail-item">
              <span class="detail-label">商品金额</span>
              <span>{{ money(detail.totalAmount) }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">优惠金额</span>
              <span>{{ Number(detail.discountAmount) > 0 ? '-' + money(detail.discountAmount) : money(detail.discountAmount) }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">运费</span>
              <span>{{ money(detail.freight) }}</span>
            </div>
            <div class="detail-item total-row">
              <span class="detail-label">实付金额</span>
              <span class="cell-price">{{ money(detail.actualAmount) }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">订单状态</span>
              <StatusTag
                :type="orderStatusType(detail.status)"
                :label="statusMap[detail.status] || '未知'"
              />
            </div>
            <div class="detail-item">
              <span class="detail-label">下单时间</span>
              <span>{{ detail.createTime || '-' }}</span>
            </div>
          </div>
        </template>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NButton, NDataTable, NDatePicker, NDrawer, NDrawerContent, NInput,
  NPagination, NSelect, NSpin, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { orderApi } from '@/api/order'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'
import type { AdminOrderPageQuery, OrderVO } from '@/api/types'

const message = useMessage()

/* ==================== 数据状态 ==================== */

const list = ref<OrderVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

/** 关键字：匹配订单号 / 收货人 / 电话（后端 like） */
const keyword = ref('')
/** 状态筛选：null=全部 */
const statusFilter = ref<number | string | null>(null)
/** 下单时间范围（formatted-value，yyyy-MM-dd） */
const dateRange = ref<string | [string, string] | null>(null)

/** 订单状态选项（后端约定：0待支付 1已支付 2已发货 3已收货 4已完成 5已取消 6已退款） */
const statusOptions = [
  { label: '待支付', value: 0 },
  { label: '已支付', value: 1 },
  { label: '已发货', value: 2 },
  { label: '已收货', value: 3 },
  { label: '已完成', value: 4 },
  { label: '已取消', value: 5 },
  { label: '已退款', value: 6 }
]

const statusMap: Record<number, string> = {
  0: '待支付', 1: '已支付', 2: '已发货', 3: '已收货',
  4: '已完成', 5: '已取消', 6: '已退款'
}

/** 订单状态 → StatusTag 语义色：0橙待付 / 1、2蓝已付已发 / 3、4绿已收完成 / 5、6灰取消退款 */
function orderStatusType(status: number): StatusType {
  if (status === 0) return 'pending-pay'
  if (status === 1 || status === 2) return 'paid'
  if (status === 3 || status === 4) return 'completed'
  return 'cancelled'
}

/* ==================== 详情抽屉 ==================== */

const showDetail = ref(false)
const detailLoading = ref(false)
const detail = ref<OrderVO | null>(null)

/** 无图占位（浅灰底 SVG，data URI 内联） */
const IMG_PLACEHOLDER =
  "data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='48' height='48'%3E%3Crect width='48' height='48' fill='%23EEF0F4'/%3E%3C/svg%3E"

function onItemImgError(e: Event) {
  ;(e.target as HTMLImageElement).src = IMG_PLACEHOLDER
}

async function openDetail(row: OrderVO) {
  showDetail.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await orderApi.adminDetail(row.id)
    detail.value = res.data || null
  } catch (e) {
    console.error('加载订单详情失败:', e)
    message.error('加载订单详情失败')
  } finally {
    detailLoading.value = false
  }
}

/* ==================== 表格 ==================== */

function rowKey(row: OrderVO) {
  return row.id
}

function money(v: number | string | null | undefined): string {
  return `¥${Number(v || 0).toFixed(2)}`
}

function countItems(row: OrderVO): number {
  return (row.items || []).reduce((sum, it) => sum + (Number(it.quantity) || 0), 0)
}

function renderBuyer(row: OrderVO) {
  return h('div', { class: 'buyer-cell' }, [
    h('div', { class: 'buyer-name' }, row.receiverName || '-'),
    row.receiverPhone ? h('div', { class: 'buyer-phone' }, row.receiverPhone) : null
  ])
}

const columns: DataTableColumns<OrderVO> = [
  {
    title: '订单号',
    key: 'orderNo',
    width: 200,
    render: (row) => h('span', { class: 'cell-mono' }, row.orderNo || '-')
  },
  { title: '买家', key: 'buyer', width: 150, render: renderBuyer },
  {
    title: '商品数',
    key: 'itemCount',
    width: 80,
    align: 'center',
    render: (row) => {
      const n = countItems(row)
      return h('span', {}, n > 0 ? String(n) : '-')
    }
  },
  {
    title: '实付金额',
    key: 'actualAmount',
    width: 120,
    render: (row) => h('span', { class: 'cell-price' }, money(row.actualAmount))
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: orderStatusType(row.status),
        label: statusMap[row.status] || '未知'
      })
  },
  {
    title: '下单时间',
    key: 'createTime',
    width: 170,
    render: (row) => h('span', { class: 'cell-muted' }, row.createTime || '-')
  },
  {
    title: '操作',
    key: 'actions',
    width: 100,
    align: 'center',
    render: (row) =>
      h(
        NButton,
        { size: 'small', quaternary: true, type: 'primary', onClick: () => openDetail(row) },
        { default: () => '查看详情' }
      )
  }
]

/* ==================== 数据加载 ==================== */

async function loadOrders() {
  loading.value = true
  try {
    const params: AdminOrderPageQuery = { page: page.value, pageSize: pageSize.value }
    if (statusFilter.value !== null && statusFilter.value !== '') {
      params.status = statusFilter.value
    }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (Array.isArray(dateRange.value) && dateRange.value.length === 2) {
      params.startTime = String(dateRange.value[0])
      params.endTime = String(dateRange.value[1])
    }
    const res = await orderApi.adminPage(params)
    list.value = res.data?.list || []
    total.value = Number(res.data?.total) || 0
  } catch (e) {
    console.error('加载订单列表失败:', e)
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadOrders()
}

function handleFilterChange() {
  page.value = 1
  loadOrders()
}

function handleReset() {
  keyword.value = ''
  statusFilter.value = null
  dateRange.value = null
  page.value = 1
  loadOrders()
}

onMounted(loadOrders)
</script>

<style scoped>
.page {
  padding: 20px;
}

/* ====== 工具条 ====== */
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 14px 16px;
  margin-bottom: 16px;
}

/* ====== 表格卡 ====== */
.table-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px;
}
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* ====== 单元格 ====== */
.cell-price {
  color: var(--ds-price, #2d7ff9);
  font-weight: 600;
  font-family: var(--ds-mono, Consolas, monospace);
}
.table-card :deep(.cell-price) {
  color: var(--ds-price, #2d7ff9);
  font-weight: 600;
  font-family: var(--ds-mono, Consolas, monospace);
}
.cell-muted {
  color: var(--ds-text-3, #98a1af);
}
.table-card :deep(.cell-muted) {
  color: var(--ds-text-3, #98a1af);
}
.table-card :deep(.cell-mono) {
  font-family: var(--ds-mono, Consolas, monospace);
}
.table-card :deep(.buyer-cell) {
  display: flex;
  flex-direction: column;
}
.table-card :deep(.buyer-name) {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
}
.table-card :deep(.buyer-phone) {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 2px;
}

/* ====== 详情抽屉 ====== */
.drawer-loading {
  display: flex;
  justify-content: center;
  padding: 60px 0;
}
.detail-section {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.detail-section:last-child {
  border-bottom: none;
}
.detail-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
  color: var(--ds-text, #1e2632);
}
.detail-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 6px 0;
  font-size: 14px;
  color: var(--ds-text, #1e2632);
}
.detail-label {
  color: var(--ds-text-3, #98a1af);
  flex-shrink: 0;
}
.detail-value {
  text-align: right;
  word-break: break-all;
}
.total-row {
  border-top: 1px dashed var(--ds-line, #e7e9ee);
  padding-top: 10px;
  margin-top: 6px;
}
.item-list {
  display: flex;
  flex-direction: column;
}
.order-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.order-item:last-child {
  border-bottom: none;
}
.item-thumb {
  width: 48px;
  height: 48px;
  border-radius: var(--ds-r-sm, 9px);
  object-fit: cover;
  background: var(--ds-bg-2, #eef0f4);
  flex-shrink: 0;
}
.item-info {
  flex: 1;
  min-width: 0;
}
.item-name {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
}
.item-sku {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 2px;
}
.item-amount {
  text-align: right;
  white-space: nowrap;
}
.item-price {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
}
.item-qty {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.item-subtotal {
  font-size: 12px;
  font-weight: 600;
}
</style>
