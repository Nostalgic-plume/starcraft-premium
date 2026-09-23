<template>
  <div class="page fade-in">
    <!-- TODO(batch4): 评价管理为前端占位，未接后端 API；数据为静态示例。待后端提供
         评价分页 / 审核 / 删除接口后替换为真实请求。 -->

    <!-- 统计概览 -->
    <div class="stat-row">
      <div v-for="s in stats" :key="s.label" class="stat-card">
        <div class="stat-label">{{ s.label }}</div>
        <div class="stat-value" :class="s.strong ? 'value-strong' : ''">{{ s.value }}</div>
      </div>
    </div>

    <!-- 工具条：状态筛选（本地过滤占位数据） -->
    <div class="toolbar">
      <n-select
        v-model:value="statusFilter"
        :options="statusOptions"
        placeholder="审核状态"
        clearable
        style="width: 140px"
      />
      <span class="toolbar-tip">占位功能：待后端接口接入</span>
    </div>

    <!-- 评价列表 -->
    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="filteredList"
        :row-key="rowKey"
        :scroll-x="1080"
      />
    </div>

    <!-- 审核弹窗（占位） -->
    <n-modal
      v-model:show="dialogVisible"
      preset="card"
      title="审核评价"
      style="width: 460px"
      :mask-closable="false"
    >
      <p v-if="currentRow" class="dialog-tip">
        评价用户：<span class="tip-strong">{{ currentRow.user }}</span>
        ，商品：{{ currentRow.product }}
      </p>
      <div class="review-content">{{ currentRow?.content }}</div>
      <template #footer>
        <n-button @click="dialogVisible = false">取消</n-button>
        <n-button type="primary" @click="todo">通过</n-button>
        <n-button type="error" @click="todo">驳回</n-button>
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
  { label: '待审核', value: '23', strong: true },
  { label: '已通过', value: '1,208' },
  { label: '已驳回', value: '64' },
  { label: '好评率', value: '94.8%' }
]

/* ==================== 评价列表（占位数据） ==================== */

interface ReviewRow {
  id: string
  product: string
  user: string
  /** 评分（1-5） */
  rating: number
  content: string
  /** 0待审核 1已通过 2已驳回 */
  status: number
  createTime: string
}

const allList: ReviewRow[] = [
  { id: '1', product: '智能蓝牙耳机 Pro', user: 'zhangsan', rating: 5, content: '音质很好，降噪效果超出预期，续航也够用。', status: 1, createTime: '2026-08-17 20:12' },
  { id: '2', product: '轻薄笔记本电脑', user: 'lisi', rating: 4, content: '整体不错，就是风扇偶尔声音有点大。', status: 1, createTime: '2026-08-17 18:45' },
  { id: '3', product: '陶瓷保温杯 500ml', user: 'wangwu', rating: 5, content: '保温效果很好，颜值在线。', status: 0, createTime: '2026-08-17 16:02' },
  { id: '4', product: '机械键盘 87 键', user: 'zhaoliu', rating: 2, content: '到手键帽有划痕，手感一般，申请换货。', status: 0, createTime: '2026-08-17 14:30' },
  { id: '5', product: '无线充电器', user: 'sunqi', rating: 1, content: '充电速度太慢了，和描述不符。', status: 2, createTime: '2026-08-16 22:18' },
  { id: '6', product: '便携蓝牙音箱', user: 'zhouba', rating: 5, content: '低音表现很棒，出去玩很方便。', status: 1, createTime: '2026-08-16 19:44' }
]

const statusFilter = ref<number | null>(null)
const filteredList = computed(() =>
  statusFilter.value === null
    ? allList
    : allList.filter((r) => r.status === statusFilter.value)
)

const statusOptions = [
  { label: '待审核', value: 0 },
  { label: '已通过', value: 1 },
  { label: '已驳回', value: 2 }
]

function reviewStatusType(status: number): StatusType {
  if (status === 0) return 'pending-pay'
  if (status === 1) return 'completed'
  return 'failed'
}

const statusTextMap: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已驳回' }

function rowKey(row: ReviewRow) {
  return row.id
}

/* ==================== 审核弹窗（占位） ==================== */

const dialogVisible = ref(false)
const currentRow = ref<ReviewRow | null>(null)

function openReviewDialog(row: ReviewRow) {
  currentRow.value = row
  dialogVisible.value = true
}

const columns: DataTableColumns<ReviewRow> = [
  {
    title: '商品',
    key: 'product',
    width: 200,
    ellipsis: { tooltip: true },
    render: (row) => h('span', {}, row.product)
  },
  {
    title: '用户',
    key: 'user',
    width: 110,
    render: (row) => h('span', { class: 'cell-mono' }, row.user)
  },
  {
    title: '评分',
    key: 'rating',
    width: 90,
    align: 'center',
    render: (row) => h('span', { class: 'cell-price' }, `${row.rating.toFixed(1)} 分`)
  },
  {
    title: '评价内容',
    key: 'content',
    minWidth: 260,
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'cell-muted' }, row.content)
  },
  {
    title: '审核状态',
    key: 'status',
    width: 110,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: reviewStatusType(row.status),
        label: statusTextMap[row.status] || '未知'
      })
  },
  {
    title: '评价时间',
    key: 'createTime',
    width: 160,
    render: (row) => h('span', { class: 'cell-muted' }, row.createTime)
  },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    align: 'center',
    render: (row) =>
      h('div', { class: 'action-btns' }, [
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'primary', onClick: () => openReviewDialog(row) },
          { default: () => '审核' }
        ),
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'error', onClick: todo },
          { default: () => '删除' }
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
.table-card :deep(.action-btns) {
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
.review-content {
  background: var(--ds-bg-2, #eef0f4);
  border-radius: var(--ds-r-sm, 9px);
  padding: 12px 14px;
  font-size: 13px;
  color: var(--ds-text, #1e2632);
  line-height: 1.7;
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
.table-card :deep(.cell-mono) {
  font-family: var(--ds-mono, Consolas, monospace);
}
</style>
