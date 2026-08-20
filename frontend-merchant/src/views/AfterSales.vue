<template>
  <div class="page fade-in">
    <!-- TODO(占位): 售后处理为前端占位，未接后端 API；列表/审核（同意/拒绝）均为静态示例。
         待后端提供本店售后单分页 / 审核接口（/api/merchant/after-sale/*，shop_id 后端注入）后替换为真实请求。 -->

    <!-- 页头 -->
    <div class="page-head">
      <div class="head-text">
        <h2 class="page-title">售后处理</h2>
        <p class="page-desc">本店售后单审核（同意 / 拒绝）（shop_id 由后端从登录态注入，前端不传）</p>
      </div>
      <div class="head-actions">
        <n-select
          v-model:value="typeFilter"
          :options="typeOptions"
          placeholder="售后类型"
          clearable
          style="width: 140px"
        />
        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          placeholder="售后状态"
          clearable
          style="width: 140px"
        />
      </div>
    </div>

    <!-- 售后列表（占位数据） -->
    <div class="table-card">
      <n-data-table :columns="columns" :data="filteredList" :row-key="rowKey" :scroll-x="1080" />
    </div>

    <!-- 审核弹窗（占位）：同意 / 拒绝 -->
    <n-modal
      v-model:show="auditVisible"
      preset="card"
      title="审核售后单"
      style="width: 460px"
      :mask-closable="false"
    >
      <p v-if="currentRow" class="dialog-tip">
        售后单：<span class="tip-strong">{{ currentRow.no }}</span>
        ，申请金额 <span class="tip-strong tip-money">{{ money(currentRow.amount) }}</span>
      </p>
      <div class="review-content">{{ currentRow?.reason }}</div>
      <n-input v-model:value="auditRemark" type="textarea" :rows="2" placeholder="审核备注（可选）" />
      <template #footer>
        <n-button @click="auditVisible = false">取消</n-button>
        <n-button type="error" @click="todo">拒绝</n-button>
        <n-button type="primary" @click="todo">同意</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NButton, NDataTable, NInput, NModal, NSelect, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'

const message = useMessage()

/** 占位提示：所有操作按钮待后端接口接入后替换为真实逻辑 */
function todo() {
  message.info('占位功能：待后端接口接入')
}

/* ==================== 售后列表（占位数据） ==================== */

interface AfterSaleRow {
  id: string
  no: string
  orderNo: string
  product: string
  /** 1仅退款 2退货退款 3换货 */
  type: number
  amount: number
  /** 0待审核 1待退款 2已退款 3已完成 4已驳回 */
  status: number
  reason: string
  createTime: string
}

const allList: AfterSaleRow[] = [
  { id: '1', no: 'AS20260818001', orderNo: '20260818100112', product: '智能蓝牙耳机 Pro', type: 1, amount: 399, status: 0, reason: '商品未按时发货，申请退款。', createTime: '2026-08-18 20:31' },
  { id: '2', no: 'AS20260818002', orderNo: '20260818098213', product: '陶瓷保温杯 500ml', type: 2, amount: 69, status: 0, reason: '杯底有裂痕，申请退货退款。', createTime: '2026-08-18 17:26' },
  { id: '3', no: 'AS20260817003', orderNo: '20260817095001', product: '机械键盘 87 键', type: 3, amount: 0, status: 1, reason: '键帽划痕，申请换货，等待商家处理。', createTime: '2026-08-17 15:02' }
]

const typeFilter = ref<number | null>(null)
const statusFilter = ref<number | null>(null)

const filteredList = computed(() => {
  let rows = allList
  if (typeFilter.value !== null) {
    rows = rows.filter((r) => r.type === typeFilter.value)
  }
  if (statusFilter.value !== null) {
    rows = rows.filter((r) => r.status === statusFilter.value)
  }
  return rows
})

const typeOptions = [
  { label: '仅退款', value: 1 },
  { label: '退货退款', value: 2 },
  { label: '换货', value: 3 }
]

const typeTextMap: Record<number, string> = { 1: '仅退款', 2: '退货退款', 3: '换货' }

const statusOptions = [
  { label: '待审核', value: 0 },
  { label: '待退款', value: 1 },
  { label: '已退款', value: 2 },
  { label: '已完成', value: 3 },
  { label: '已驳回', value: 4 }
]

function afterSaleStatusType(status: number): StatusType {
  if (status === 0) return 'pending-pay'
  if (status === 1) return 'pending-ship'
  if (status === 2) return 'paid'
  if (status === 3) return 'completed'
  return 'failed'
}

const statusTextMap: Record<number, string> = { 0: '待审核', 1: '待退款', 2: '已退款', 3: '已完成', 4: '已驳回' }

function rowKey(row: AfterSaleRow) {
  return row.id
}

function money(v: number): string {
  return `¥${v.toFixed(2)}`
}

/* ==================== 审核弹窗（占位） ==================== */

const auditVisible = ref(false)
const currentRow = ref<AfterSaleRow | null>(null)
const auditRemark = ref('')

function openAudit(row: AfterSaleRow) {
  currentRow.value = row
  auditRemark.value = ''
  auditVisible.value = true
}

/* ==================== 表格列 ==================== */

const columns: DataTableColumns<AfterSaleRow> = [
  { title: '售后单号', key: 'no', width: 170, render: (row) => h('span', { class: 'cell-mono' }, row.no) },
  { title: '关联订单', key: 'orderNo', width: 170, render: (row) => h('span', { class: 'cell-mono cell-muted' }, row.orderNo) },
  { title: '商品', key: 'product', width: 180, ellipsis: { tooltip: true }, render: (row) => h('span', {}, row.product) },
  { title: '类型', key: 'type', width: 100, align: 'center', render: (row) => h('span', {}, typeTextMap[row.type] || '-') },
  {
    title: '申请金额',
    key: 'amount',
    width: 110,
    align: 'center',
    render: (row) => h('span', { class: 'cell-price' }, row.amount > 0 ? money(row.amount) : '—')
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: afterSaleStatusType(row.status),
        label: statusTextMap[row.status] || '未知'
      })
  },
  { title: '申请时间', key: 'createTime', width: 160, render: (row) => h('span', { class: 'cell-muted' }, row.createTime) },
  {
    title: '操作',
    key: 'actions',
    width: 100,
    align: 'center',
    render: (row) => {
      // 仅待审核（status=0）售后单可审核
      if (Number(row.status) !== 0) {
        return h('span', { class: 'cell-muted' }, '-')
      }
      return h(
        NButton,
        { size: 'small', quaternary: true, type: 'primary', onClick: () => openAudit(row) },
        { default: () => '审核' }
      )
    }
  }
]
</script>

<style scoped>
.page {
  padding: 20px;
}

/* ====== 页头 ====== */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.head-text {
  min-width: 0;
}
.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  line-height: 1.3;
}
.page-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

/* ====== 表格卡 ====== */
.table-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px;
}

/* ====== 弹窗 ====== */
.dialog-tip {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
.tip-strong {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}
.tip-money {
  color: var(--ds-price, #2d7ff9);
  font-family: var(--ds-mono, Consolas, monospace);
}
.review-content {
  background: var(--ds-bg-2, #eef0f4);
  border-radius: var(--ds-r-sm, 9px);
  padding: 12px 14px;
  font-size: 13px;
  color: var(--ds-text, #1e2632);
  line-height: 1.7;
  margin-bottom: 12px;
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
</style>
