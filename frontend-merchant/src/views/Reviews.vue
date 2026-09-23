<template>
  <div class="page fade-in">
    <!-- TODO(占位): 评价管理为前端占位，未接后端 API；列表/回复均为静态示例。
         待后端提供本店评价分页 / 商家回复接口（/api/merchant/review/*，shop_id 后端注入）后替换为真实请求。 -->

    <!-- 页头 -->
    <div class="page-head">
      <div class="head-text">
        <h2 class="page-title">评价管理</h2>
        <p class="page-desc">本店商品评价列表与商家回复（shop_id 由后端从登录态注入，前端不传）</p>
      </div>
      <div class="head-actions">
        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          placeholder="回复状态"
          clearable
          style="width: 140px"
        />
      </div>
    </div>

    <!-- 评价列表（占位数据） -->
    <div class="table-card">
      <n-data-table :columns="columns" :data="filteredList" :row-key="rowKey" :scroll-x="1080" />
    </div>

    <!-- 回复弹窗（占位） -->
    <n-modal
      v-model:show="replyVisible"
      preset="card"
      title="回复评价"
      style="width: 480px"
      :mask-closable="false"
    >
      <p v-if="currentRow" class="dialog-tip">
        用户：<span class="tip-strong">{{ currentRow.user }}</span>
        ，商品：{{ currentRow.product }}
      </p>
      <div class="review-content">{{ currentRow?.content }}</div>
      <n-input v-model:value="replyText" type="textarea" :rows="3" placeholder="请输入商家回复内容" />
      <template #footer>
        <n-button @click="replyVisible = false">取消</n-button>
        <n-button type="primary" @click="todo">提交回复</n-button>
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

/* ==================== 评价列表（占位数据） ==================== */

interface ReviewRow {
  id: string
  product: string
  user: string
  /** 评分（1-5） */
  rating: number
  content: string
  /** 0待回复 1已回复 */
  status: number
  createTime: string
}

const allList: ReviewRow[] = [
  { id: '1', product: '智能蓝牙耳机 Pro', user: 'zhangsan', rating: 5, content: '音质很好，降噪效果超出预期，续航也够用。', status: 0, createTime: '2026-08-17 20:12' },
  { id: '2', product: '轻薄笔记本电脑', user: 'lisi', rating: 4, content: '整体不错，就是风扇偶尔声音有点大。', status: 0, createTime: '2026-08-17 18:45' },
  { id: '3', product: '陶瓷保温杯 500ml', user: 'wangwu', rating: 5, content: '保温效果很好，颜值在线，商家发货也很快。', status: 1, createTime: '2026-08-17 16:02' }
]

const statusFilter = ref<number | null>(null)
const filteredList = computed(() =>
  statusFilter.value === null
    ? allList
    : allList.filter((r) => r.status === statusFilter.value)
)

const statusOptions = [
  { label: '待回复', value: 0 },
  { label: '已回复', value: 1 }
]

function reviewStatusType(status: number): StatusType {
  if (status === 0) return 'pending-pay'
  return 'completed'
}

const statusTextMap: Record<number, string> = { 0: '待回复', 1: '已回复' }

function rowKey(row: ReviewRow) {
  return row.id
}

/* ==================== 回复弹窗（占位） ==================== */

const replyVisible = ref(false)
const currentRow = ref<ReviewRow | null>(null)
const replyText = ref('')

function openReply(row: ReviewRow) {
  currentRow.value = row
  replyText.value = ''
  replyVisible.value = true
}

/* ==================== 表格列 ==================== */

const columns: DataTableColumns<ReviewRow> = [
  { title: '商品', key: 'product', width: 200, ellipsis: { tooltip: true }, render: (row) => h('span', {}, row.product) },
  { title: '用户', key: 'user', width: 110, render: (row) => h('span', { class: 'cell-mono' }, row.user) },
  {
    title: '评分',
    key: 'rating',
    width: 90,
    align: 'center',
    render: (row) => h('span', { class: 'cell-price' }, `${row.rating.toFixed(1)} 分`)
  },
  { title: '评价内容', key: 'content', minWidth: 260, ellipsis: { tooltip: true }, render: (row) => h('span', { class: 'cell-muted' }, row.content) },
  {
    title: '回复状态',
    key: 'status',
    width: 110,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: reviewStatusType(row.status),
        label: statusTextMap[row.status] || '未知'
      })
  },
  { title: '评价时间', key: 'createTime', width: 160, render: (row) => h('span', { class: 'cell-muted' }, row.createTime) },
  {
    title: '操作',
    key: 'actions',
    width: 100,
    align: 'center',
    render: (row) =>
      h(
        NButton,
        { size: 'small', quaternary: true, type: 'primary', onClick: () => openReply(row) },
        { default: () => '回复' }
      )
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
