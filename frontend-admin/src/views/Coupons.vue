<template>
  <div class="page fade-in">
    <!-- TODO(batch4): 优惠券管理为前端占位，未接后端 API；数据为静态示例。待后端提供
         coupon CRUD / 发券 / 统计接口后替换为真实请求。 -->

    <!-- 统计概览 -->
    <div class="stat-row">
      <div v-for="s in stats" :key="s.label" class="stat-card">
        <div class="stat-label">{{ s.label }}</div>
        <div class="stat-value">{{ s.value }}</div>
      </div>
    </div>

    <!-- 工具条：新建 / 发券 -->
    <div class="toolbar">
      <n-button type="primary" @click="todo">新建优惠券</n-button>
      <n-button secondary @click="todo">发券</n-button>
      <span class="toolbar-tip">占位功能：待后端接口接入</span>
    </div>

    <!-- 券列表 -->
    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :row-key="rowKey"
        :scroll-x="1080"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { h, ref } from 'vue'
import { NButton, NDataTable, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'

const message = useMessage()

/** 占位提示：所有操作按钮待后端接口接入后替换为真实逻辑 */
function todo() {
  message.info('占位功能：待后端接口接入')
}

/* ==================== 统计概览（占位） ==================== */

const stats = [
  { label: '累计发券', value: '3,200' },
  { label: '已领取', value: '1,586' },
  { label: '已核销', value: '742' },
  { label: '优惠金额', value: '¥12,680.50' }
]

/* ==================== 券列表（占位数据） ==================== */

interface CouponRow {
  id: string
  name: string
  /** 面额（元） */
  amount: number
  total: number
  received: number
  used: number
  startDate: string
  endDate: string
  /** 0生效中 1已停用 2已过期 */
  status: number
}

const list = ref<CouponRow[]>([
  { id: '1', name: '新客立减 10 元', amount: 10, total: 1000, received: 642, used: 318, startDate: '2026-08-01', endDate: '2026-08-31', status: 0 },
  { id: '2', name: '满 199 减 30', amount: 30, total: 800, received: 512, used: 226, startDate: '2026-08-01', endDate: '2026-08-31', status: 0 },
  { id: '3', name: '周年庆 5 折券', amount: 50, total: 500, received: 232, used: 88, startDate: '2026-07-15', endDate: '2026-08-15', status: 2 },
  { id: '4', name: '老客回归 15 元', amount: 15, total: 600, received: 150, used: 96, startDate: '2026-08-10', endDate: '2026-09-10', status: 0 },
  { id: '5', name: '品类券·数码 20 元', amount: 20, total: 300, received: 50, used: 14, startDate: '2026-08-01', endDate: '2026-08-31', status: 1 }
])

function couponStatusType(status: number): StatusType {
  if (status === 0) return 'on-sale'
  if (status === 2) return 'cancelled'
  return 'disabled'
}

const statusTextMap: Record<number, string> = { 0: '生效中', 1: '已停用', 2: '已过期' }

function rowKey(row: CouponRow) {
  return row.id
}

const columns: DataTableColumns<CouponRow> = [
  {
    title: '券名称',
    key: 'name',
    width: 200,
    render: (row) => h('span', {}, row.name)
  },
  {
    title: '面额',
    key: 'amount',
    width: 100,
    align: 'center',
    render: (row) => h('span', { class: 'cell-price' }, `¥${row.amount.toFixed(2)}`)
  },
  {
    title: '发放量',
    key: 'total',
    width: 100,
    align: 'center',
    render: (row) => h('span', {}, String(row.total))
  },
  {
    title: '已领取',
    key: 'received',
    width: 100,
    align: 'center',
    render: (row) => h('span', {}, String(row.received))
  },
  {
    title: '已核销',
    key: 'used',
    width: 100,
    align: 'center',
    render: (row) => h('span', { class: 'cell-muted' }, String(row.used))
  },
  {
    title: '有效期',
    key: 'period',
    width: 220,
    render: (row) => h('span', { class: 'cell-muted' }, `${row.startDate} ~ ${row.endDate}`)
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: couponStatusType(row.status),
        label: statusTextMap[row.status] || '未知'
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    align: 'center',
    render: () =>
      h('div', { class: 'action-btns' }, [
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'primary', onClick: todo },
          { default: () => '编辑' }
        ),
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'error', onClick: todo },
          { default: () => '停用' }
        )
      ])
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
.table-card :deep(.action-btns) {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  white-space: nowrap;
}

/* ====== 单元格 ====== */
.table-card :deep(.cell-price) {
  color: var(--ds-price, #2d7ff9);
  font-weight: 600;
  font-family: var(--ds-mono, Consolas, monospace);
}
.table-card :deep(.cell-muted) {
  color: var(--ds-text-3, #98a1af);
}
</style>
