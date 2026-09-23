<template>
  <div class="page fade-in">
    <!-- TODO(占位): 本店优惠券管理为前端占位，未接后端 API；列表/新建/发券均为静态示例。
         待后端提供本店券 CRUD / 发券接口（/api/merchant/coupon/*，shop_id 后端注入）后替换为真实请求。 -->

    <!-- 页头 -->
    <div class="page-head">
      <div class="head-text">
        <h2 class="page-title">本店优惠券管理</h2>
        <p class="page-desc">本店优惠券列表、新建与发放（shop_id 由后端从登录态注入，前端不传）</p>
      </div>
      <div class="head-actions">
        <n-button secondary @click="openIssue">发券</n-button>
        <n-button type="primary" @click="openCreate">新建优惠券</n-button>
      </div>
    </div>

    <!-- 券列表（占位数据） -->
    <div class="table-card">
      <n-data-table :columns="columns" :data="list" :row-key="rowKey" :scroll-x="1080" />
    </div>

    <!-- 新建券抽屉（占位）：名称/面额/门槛/数量/有效期 -->
    <n-drawer v-model:show="drawerVisible" :width="420">
      <n-drawer-content title="新建优惠券" closable>
        <n-form label-placement="top" :show-feedback="false">
          <n-form-item label="券名称">
            <n-input v-model:value="form.name" placeholder="如：满 199 减 30" />
          </n-form-item>
          <n-form-item label="面额（元）">
            <n-input-number v-model:value="form.amount" :min="0" :precision="2" style="width: 100%" placeholder="优惠金额" />
          </n-form-item>
          <n-form-item label="使用门槛（元）">
            <n-input-number v-model:value="form.minAmount" :min="0" :precision="2" style="width: 100%" placeholder="满多少可用，0 为无门槛" />
          </n-form-item>
          <n-form-item label="发放数量（张）">
            <n-input-number v-model:value="form.total" :min="1" style="width: 100%" placeholder="券发行总量" />
          </n-form-item>
          <n-form-item label="有效期">
            <n-date-picker v-model:value="form.period" type="daterange" style="width: 100%" />
          </n-form-item>
        </n-form>
        <template #footer>
          <n-button @click="drawerVisible = false">取消</n-button>
          <n-button type="primary" @click="todo">创建</n-button>
        </template>
      </n-drawer-content>
    </n-drawer>

    <!-- 发券弹窗（占位）：选券 + 数量 -->
    <n-modal v-model:show="issueVisible" preset="card" title="发放优惠券" style="width: 420px" :mask-closable="false">
      <n-form label-placement="top" :show-feedback="false">
        <n-form-item label="选择券">
          <n-select v-model:value="issueCouponId" :options="couponSelectOptions" placeholder="请选择券" />
        </n-form-item>
        <n-form-item label="发放数量（张）">
          <n-input-number v-model:value="issueCount" :min="1" style="width: 100%" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="issueVisible = false">取消</n-button>
        <n-button type="primary" @click="todo">确认发放</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import {
  NButton, NDataTable, NDatePicker, NDrawer, NDrawerContent, NForm, NFormItem,
  NInput, NInputNumber, NModal, NSelect, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'

const message = useMessage()

/** 占位提示：所有操作按钮待后端接口接入后替换为真实逻辑 */
function todo() {
  message.info('占位功能：待后端接口接入')
}

/* ==================== 券列表（占位数据） ==================== */

interface CouponRow {
  id: string
  name: string
  /** 面额（元） */
  amount: number
  /** 使用门槛（元），0 为无门槛 */
  minAmount: number
  total: number
  received: number
  used: number
  startDate: string
  endDate: string
  /** 0生效中 1已停用 2已过期 */
  status: number
}

const list = ref<CouponRow[]>([
  { id: '1', name: '新客立减 10 元', amount: 10, minAmount: 0, total: 1000, received: 642, used: 318, startDate: '2026-08-01', endDate: '2026-08-31', status: 0 },
  { id: '2', name: '满 199 减 30', amount: 30, minAmount: 199, total: 800, received: 512, used: 226, startDate: '2026-08-01', endDate: '2026-08-31', status: 0 },
  { id: '3', name: '周年庆 50 元券', amount: 50, minAmount: 499, total: 500, received: 232, used: 88, startDate: '2026-07-15', endDate: '2026-08-15', status: 2 }
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

function money(v: number): string {
  return `¥${v.toFixed(2)}`
}

/** 使用门槛文案：0=无门槛，否则 满 ¥xx */
function renderThreshold(row: CouponRow) {
  return h('span', { class: 'cell-muted' }, row.minAmount > 0 ? `满 ${money(row.minAmount)}` : '无门槛')
}

/* ==================== 新建券抽屉（占位） ==================== */

const drawerVisible = ref(false)

interface CouponFormState {
  name: string
  amount: number | null
  minAmount: number | null
  total: number | null
  /** 有效期 [开始, 结束] 时间戳 */
  period: [number, number] | null
}

const form = ref<CouponFormState>({ name: '', amount: null, minAmount: null, total: null, period: null })

function openCreate() {
  drawerVisible.value = true
}

/* ==================== 发券弹窗（占位） ==================== */

const issueVisible = ref(false)
const issueCouponId = ref<string | null>(null)
const issueCount = ref(1)

const couponSelectOptions = computed(() => list.value.map((c) => ({ label: c.name, value: c.id })))

function openIssue() {
  issueVisible.value = true
}

/* ==================== 表格列 ==================== */

const columns: DataTableColumns<CouponRow> = [
  { title: '券名称', key: 'name', width: 200, render: (row) => h('span', {}, row.name) },
  {
    title: '面额',
    key: 'amount',
    width: 100,
    align: 'center',
    render: (row) => h('span', { class: 'cell-price' }, money(row.amount))
  },
  { title: '使用门槛', key: 'minAmount', width: 120, align: 'center', render: renderThreshold },
  { title: '发放量', key: 'total', width: 90, align: 'center', render: (row) => h('span', {}, String(row.total)) },
  { title: '已领取', key: 'received', width: 90, align: 'center', render: (row) => h('span', {}, String(row.received)) },
  { title: '已核销', key: 'used', width: 90, align: 'center', render: (row) => h('span', { class: 'cell-muted' }, String(row.used)) },
  {
    title: '有效期',
    key: 'period',
    width: 210,
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
