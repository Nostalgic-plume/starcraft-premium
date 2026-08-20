<template>
  <div class="page fade-in">
    <!-- 工具条：关键字 + 状态 -->
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
      <n-button type="primary" @click="handleSearch">搜索</n-button>
      <n-button quaternary @click="handleReset">重置</n-button>
    </div>

    <!-- 关键字筛选为前端过滤（后端 /merchant/order/page 无 keyword 参数） -->
    <n-alert v-if="keyword.trim()" type="info" :bordered="false" class="filter-tip">
      后端订单分页接口暂不支持关键字筛选，当前为本店订单内按单号 / 收货人 / 电话过滤（上限内）。
    </n-alert>

    <!-- 订单表格 -->
    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :scroll-x="1020"
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

    <!-- 发货弹窗 -->
    <n-modal
      v-model:show="deliverVisible"
      preset="dialog"
      title="发货"
      :positive-text="'确认发货'"
      :negative-text="'取消'"
      :on-positive-click="handleDeliver"
    >
      <div class="deliver-form">
        <div class="deliver-field">
          <span class="deliver-label">订单号</span>
          <span class="cell-mono">{{ deliverOrderNo }}</span>
        </div>
        <div class="deliver-field">
          <span class="deliver-label">物流单号</span>
          <n-input
            v-model:value="deliverForm.logisticsNo"
            placeholder="请输入物流单号"
            @keyup.enter="handleDeliver"
          />
        </div>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NButton, NDataTable, NDrawer, NDrawerContent, NInput,
  NModal, NPagination, NSelect, NSpin, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { merchantApi } from '@/api/merchant'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'
import type { OrderVO } from '@/api/types'

const message = useMessage()

/* ==================== 数据状态 ==================== */

const list = ref<OrderVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

/** 关键字：匹配订单号 / 收货人 / 电话（后端不支持，前端过滤） */
const keyword = ref('')
/** 状态筛选：null=全部 */
const statusFilter = ref<number | null>(null)

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

/**
 * 订单状态 → StatusTag 语义色：0橙待付 / 1、2蓝已付已发 / 3、4绿已收完成 / 5、6灰取消退款
 */
function orderStatusType(status: number): StatusType {
  if (status === 0) return 'pending-pay'
  if (status === 1 || status === 2) return 'paid'
  if (status === 3 || status === 4) return 'completed'
  return 'cancelled'
}

/**
 * 后端 /merchant/order/page 无 keyword 参数，关键字筛选在前端完成。
 * 触发时拉取 status 匹配全量（上限内，口径同 Dashboard 组装），前端过滤 + 前端分页。
 */
const FILTER_ALL_LIMIT = 999

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
    const res = await merchantApi.orderDetail(row.id)
    detail.value = res.data || null
  } catch (e) {
    console.error('加载订单详情失败:', e)
    message.error('加载订单详情失败')
  } finally {
    detailLoading.value = false
  }
}

/* ==================== 发货 ==================== */

const deliverVisible = ref(false)
const deliverOrderNo = ref('')
const deliverForm = ref<{ orderId: string; logisticsNo: string }>({ orderId: '', logisticsNo: '' })

function openDeliver(row: OrderVO) {
  deliverForm.value = { orderId: row.id, logisticsNo: '' }
  deliverOrderNo.value = row.orderNo || ''
  deliverVisible.value = true
}

/** 返回 boolean 控制 NModal dialog 关闭：成功关闭 / 失败保持打开 */
async function handleDeliver(): Promise<boolean> {
  if (!deliverForm.value.logisticsNo.trim()) {
    message.warning('请输入物流单号')
    return false
  }
  try {
    await merchantApi.orderDeliver({
      orderId: deliverForm.value.orderId,
      logisticsNo: deliverForm.value.logisticsNo.trim()
    })
    message.success('发货成功')
    deliverVisible.value = false
    loadOrders()
    return true
  } catch (e) {
    console.error('发货失败:', e)
    message.error('发货失败')
    return false
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

function renderGoods(row: OrderVO) {
  const items = row.items || []
  if (items.length === 0) return h('span', { class: 'cell-muted' }, '-')
  const first = items[0]
  const cnt = countItems(row)
  const sub = cnt > 1 ? `${cnt} 件` : '1 件'
  return h('div', { class: 'goods-cell' }, [
    h('div', { class: 'goods-name' }, first.productName),
    h(
      'div',
      { class: 'goods-sub' },
      items.length > 1 ? `${sub} / ${items.length} 种规格` : sub
    )
  ])
}

function renderActions(row: OrderVO) {
  const children = [
    h(
      NButton,
      { size: 'small', quaternary: true, type: 'primary', onClick: () => openDetail(row) },
      { default: () => '查看详情' }
    )
  ]
  // 仅已支付（status=1）订单可发货（后端 merchantDeliver 校验）
  if (Number(row.status) === 1) {
    children.push(
      h(
        NButton,
        { size: 'small', quaternary: true, type: 'success', onClick: () => openDeliver(row) },
        { default: () => '发货' }
      )
    )
  }
  return h('div', { class: 'action-cell' }, children)
}

const columns: DataTableColumns<OrderVO> = [
  {
    title: '订单号',
    key: 'orderNo',
    width: 200,
    render: (row) => h('span', { class: 'cell-mono' }, row.orderNo || '-')
  },
  { title: '买家', key: 'buyer', width: 150, render: renderBuyer },
  { title: '商品', key: 'goods', minWidth: 180, render: renderGoods },
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
  { title: '操作', key: 'actions', width: 150, align: 'center', render: renderActions }
]

/* ==================== 数据加载 ==================== */

/** 前端分页切片（关键字本地过滤后分页用） */
function paginate<T>(arr: T[], p: number, size: number): T[] {
  return arr.slice((p - 1) * size, p * size)
}

async function loadOrders() {
  loading.value = true
  try {
    const kw = keyword.value.trim()
    const statusParams = statusFilter.value !== null ? { status: statusFilter.value } : {}
    if (!kw) {
      // 后端分页（/merchant/order/page 支持 status）
      const res = await merchantApi.orderPage({
        page: page.value,
        pageSize: pageSize.value,
        ...statusParams
      })
      list.value = res.data?.list || []
      total.value = Number(res.data?.total) || 0
    } else {
      // 后端无 keyword → 拉取 status 匹配全量，前端按单号/收货人/电话过滤 + 前端分页
      const res = await merchantApi.orderPage({ page: 1, pageSize: FILTER_ALL_LIMIT, ...statusParams })
      const kwLow = kw.toLowerCase()
      const filtered = (res.data?.list || []).filter(
        (o) =>
          (o.orderNo || '').toLowerCase().includes(kwLow) ||
          (o.receiverName || '').includes(kwLow) ||
          (o.receiverPhone || '').includes(kwLow)
      )
      total.value = filtered.length
      list.value = paginate(filtered, page.value, pageSize.value)
    }
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
  page.value = 1
  loadOrders()
}

onMounted(loadOrders)
</script>

<style scoped>
.page {
  padding: 20px;
}

.fade-in {
  animation: fadeIn 0.3s ease;
}
@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
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

/* ====== 关键字筛选提示 ====== */
.filter-tip {
  margin-bottom: 14px;
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
.cell-muted {
  color: var(--ds-text-3, #98a1af);
}
.cell-mono {
  font-family: var(--ds-mono, Consolas, monospace);
}
.buyer-cell {
  display: flex;
  flex-direction: column;
}
.buyer-name {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
}
.buyer-phone {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 2px;
}
.goods-cell {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.goods-name {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.goods-sub {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 2px;
}
.action-cell {
  display: inline-flex;
  gap: 4px;
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

/* ====== 发货弹窗 ====== */
.deliver-form {
  padding-top: 8px;
}
.deliver-field {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.deliver-field:last-child {
  margin-bottom: 0;
}
.deliver-label {
  flex-shrink: 0;
  color: var(--ds-text-3, #98a1af);
  font-size: 14px;
}
</style>
