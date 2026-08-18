<template>
  <div class="page fade-in">
    <!-- TODO(batch4): 售后管理为前端占位，未接后端 API；数据为静态示例。待后端提供
         售后单分页 / 审核 / 退款接口后替换为真实请求。 -->

    <!-- 统计概览 -->
    <div class="stat-row">
      <div v-for="s in stats" :key="s.label" class="stat-card">
        <div class="stat-label">{{ s.label }}</div>
        <div class="stat-value" :class="s.strong ? 'value-strong' : ''">{{ s.value }}</div>
      </div>
    </div>

    <!-- 工具条：类型 / 状态筛选（本地过滤占位数据） -->
    <div class="toolbar">
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
      <span class="toolbar-tip">占位功能：待后端接口接入</span>
    </div>

    <!-- 售后列表 -->
    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="filteredList"
        :row-key="rowKey"
        :scroll-x="1120"
      />
    </div>

    <!-- 审核 / 退款弹窗（占位） -->
    <n-modal
      v-model:show="dialogVisible"
      preset="card"
      :title="dialogAction === 'refund' ? '确认退款' : '审核售后单'"
      style="width: 460px"
      :mask-closable="false"
    >
      <p v-if="currentRow" class="dialog-tip">
        售后单：<span class="tip-strong">{{ currentRow.no }}</span>
        ，申请金额 <span class="tip-strong tip-money">{{ money(currentRow.amount) }}</span>
      </p>
      <div class="review-content">{{ currentRow?.reason }}</div>
      <template #footer>
        <n-button @click="dialogVisible = false">取消</n-button>
        <n-button type="primary" @click="todo">确定{{ dialogAction === 'refund' ? '退款' : '通过' }}</n-button>
        <n-button v-if="dialogAction === 'audit'" type="error" @click="todo">驳回</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NButton, NDataTable, NSelect, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'

const message = useMessage()

/** 占位提示：所有操作按钮待后端接口接入后替换为真实逻辑 */
function todo() {
  message.info('占位功能：待后端接口接入')
}

/* ==================== 统计概览（占位） ==================== */

const stats = [
  { label: '待审核', value: '12', strong: true },
  { label: '待退款', value: '6', strong: true },
  { label: '已退款', value: '148' },
  { label: '退款金额', value: '¥8,320.00' }
]

/* ==================== 售后列表（占位数据） ==================== */

interface AfterSaleRow {
  id: string
  no: string
  orderNo: string
  product: string
  /** 1退款 2退货退款 3换货 */
  type: number
  amount: number
  /** 0待审核 1待退款 2已退款 3已完成 4已驳回 */
  status: number
  reason: string
  createTime: string
}

const allList: AfterSaleRow[] = [
  { id: '1', no: 'AS20260817001', orderNo: '20260817100112', product: '智能蓝牙耳机 Pro', type: 1, amount: 399, status: 0, reason: '商品未按时发货，申请退款。', createTime: '2026-08-17 20:31' },
  { id: '2', no: 'AS20260817002', orderNo: '20260817098213', product: '陶瓷保温杯 500ml', type: 2, amount: 69, status: 1, reason: '杯底有裂痕，申请退货退款。', createTime: '2026-08-17 17:26' },
  { id: '3', no: 'AS20260817003', orderNo: '20260817095001', product: '机械键盘 87 键', type: 3, amount: 0, status: 0, reason: '键帽划痕，申请换货。', createTime: '2026-08-17 15:02' },
  { id: '4', no: 'AS20260816004', orderNo: '20260816112008', product: '无线充电器', type: 1, amount: 129, status: 2, reason: '充电速度与描述不符。', createTime: '2026-08-16 22:40' },
  { id: '5', no: 'AS20260816005', orderNo: '20260816023410', product: '轻薄笔记本电脑', type: 2, amount: 5499, status: 3, reason: '收到货后不符合预期，已退货退款完成。', createTime: '2026-08-16 12:18' },
  { id: '6', no: 'AS20260815006', orderNo: '20260815087102', product: '便携蓝牙音箱', type: 1, amount: 259, status: 4, reason: '超过无理由退换期限，驳回。', createTime: '2026-08-15 09:55' }
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

/* ==================== 审核 / 退款弹窗（占位） ==================== */

const dialogVisible = ref(false)
const dialogAction = ref<'audit' | 'refund'>('audit')
const currentRow = ref<AfterSaleRow | null>(null)

function openAuditDialog(row: AfterSaleRow) {
  dialogAction.value = 'audit'
  currentRow.value = row
  dialogVisible.value = true
}

function openRefundDialog(row: AfterSaleRow) {
  dialogAction.value = 'refund'
  currentRow.value = row
  dialogVisible.value = true
}

const columns: DataTableColumns<AfterSaleRow> = [
  {
    title: '售后单号',
    key: 'no',
    width: 170,
    render: (row) => h('span', { class: 'cell-mono' }, row.no)
  },
  {
    title: '关联订单',
    key: 'orderNo',
    width: 170,
    render: (row) => h('span', { class: 'cell-mono cell-muted' }, row.orderNo)
  },
  {
    title: '商品',
    key: 'product',
    width: 180,
    ellipsis: { tooltip: true },
    render: (row) => h('span', {}, row.product)
  },
  {
    title: '类型',
    key: 'type',
    width: 100,
    align: 'center',
    render: (row) => h('span', {}, typeTextMap[row.type] || '-')
  },
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
  {
    title: '申请时间',
    key: 'createTime',
    width: 160,
    render: (row) => h('span', { class: 'cell-muted' }, row.createTime)
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    align: 'center',
    render: (row) => {
      const btns = []
      if (row.status === 0) {
        btns.push(
          h(
            NButton,
            { size: 'small', quaternary: true, type: 'primary', onClick: () => openAuditDialog(row) },
            { default: () => '审核' }
          )
        )
      }
      if (row.status === 1) {
        btns.push(
          h(
            NButton,
            { size: 'small', quaternary: true, type: 'warning', onClick: () => openRefundDialog(row) },
            { default: () => '退款' }
          )
        )
      }
      return h('div', { class: 'action-btns' }, btns)
    }
  }
]
</script>

<style scoped>
.page {
  padding: 20px;
}

/* ====== 统计概览 ====== */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.stat-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px 18px;
}
.stat-label {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.stat-value {
  font-family: var(--ds-mono, Consolas, monospace);
  font-size: 22px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin-top: 6px;
  line-height: 1.2;
}
.value-strong {
  color: var(--ds-orange, #e08600);
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
.toolbar-tip {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

/* ====== 表格卡 ====== */
.table-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px;
}
.action-btns {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  white-space: nowrap;
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
