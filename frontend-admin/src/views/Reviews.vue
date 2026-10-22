<template>
  <div class="page fade-in">
    <!-- 工具条：状态筛选 + 商品名搜索 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <n-input
          v-model:value="keyword"
          placeholder="搜索商品名称"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          placeholder="状态筛选"
          clearable
          style="width: 140px"
          @update:value="handleSearch"
        />
        <n-button type="primary" @click="handleSearch">搜索</n-button>
      </div>
    </div>

    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :scroll-x="1100"
      />
      <div class="table-footer">
        <n-pagination
          v-if="total > 0"
          v-model:page="page"
          :page-size="pageSize"
          :item-count="total"
          :page-slot="5"
          @update:page="loadList"
        />
      </div>
    </div>

    <!-- 回复弹窗 -->
    <n-modal
      v-model:show="replyVisible"
      preset="card"
      title="回复评价"
      style="width: 520px"
      :mask-closable="false"
    >
      <p v-if="replyTarget" class="dialog-tip">
        商品：<span class="tip-strong">{{ replyTarget.productName }}</span>
      </p>
      <p v-if="replyTarget?.content" class="quote">“{{ replyTarget.content }}”</p>
      <n-input
        v-model:value="replyText"
        type="textarea"
        :rows="4"
        :maxlength="500"
        show-count
        placeholder="输入回复内容"
      />
      <template #footer>
        <n-button @click="replyVisible = false">取消</n-button>
        <n-button type="primary" :loading="replying" @click="submitReply">回复</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NButton, NDataTable, NInput, NPagination, NRate, NSelect, NTag,
  useDialog, useMessage,
  type DataTableColumns
} from 'naive-ui'
import { reviewAdminApi, type AdminReviewVO } from '@/api/review'

const message = useMessage()
const dialog = useDialog()

const list = ref<AdminReviewVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref<number | null>(null)

const replyVisible = ref(false)
const replying = ref(false)
const replyText = ref('')
const replyTarget = ref<AdminReviewVO | null>(null)

const statusOptions = [
  { label: '显示中', value: 1 },
  { label: '已隐藏', value: 0 }
]

const rowKey = (row: AdminReviewVO) => row.id

const columns: DataTableColumns<AdminReviewVO> = [
  {
    title: '商品',
    key: 'productName',
    minWidth: 220,
    render: row => h('div', { class: 'product-cell' }, [
      row.productImage
        ? h('img', { src: row.productImage, style: 'width:36px;height:36px;object-fit:cover;border-radius:6px' })
        : null,
      h('span', { style: 'margin-left:8px' }, row.productName)
    ])
  },
  { title: '用户', key: 'nickname', width: 120, render: row => row.nickname || '—' },
  {
    title: '评分',
    key: 'rating',
    width: 140,
    render: row => h(NRate, { value: row.rating, readonly: true, size: 'small' })
  },
  { title: '内容', key: 'content', minWidth: 240, ellipsis: { tooltip: true } },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: row => h(NTag, { size: 'small', type: row.status === 1 ? 'success' : 'warning' },
      { default: () => (row.status === 1 ? '显示中' : '已隐藏') })
  },
  { title: '时间', key: 'createTime', width: 170 },
  {
    title: '操作',
    key: 'actions',
    width: 220,
    render: row => h('div', { class: 'row-actions' }, [
      h(NButton, {
        size: 'small',
        quaternary: true,
        onClick: () => toggleStatus(row)
      }, { default: () => (row.status === 1 ? '隐藏' : '显示') }),
      h(NButton, {
        size: 'small',
        quaternary: true,
        onClick: () => openReply(row)
      }, { default: () => '回复' }),
      h(NButton, {
        size: 'small',
        quaternary: true,
        type: 'error',
        onClick: () => handleDelete(row)
      }, { default: () => '删除' })
    ])
  }
]

async function loadList() {
  loading.value = true
  try {
    const res = await reviewAdminApi.page({
      page: page.value,
      pageSize: pageSize.value,
      status: statusFilter.value ?? undefined,
      keyword: keyword.value || undefined
    })
    list.value = res.data?.list || []
    total.value = Number(res.data?.total) || 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadList()
}

async function toggleStatus(row: AdminReviewVO) {
  const target = row.status === 1 ? 0 : 1
  await reviewAdminApi.updateStatus(row.id, target)
  message.success(target === 1 ? '评价已显示' : '评价已隐藏')
  await loadList()
}

function handleDelete(row: AdminReviewVO) {
  dialog.warning({
    title: '删除评价',
    content: `确定删除「${row.productName}」的这条评价吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await reviewAdminApi.remove(row.id)
      message.success('评价已删除')
      await loadList()
    }
  })
}

function openReply(row: AdminReviewVO) {
  replyTarget.value = row
  replyText.value = row.reply || ''
  replyVisible.value = true
}

async function submitReply() {
  if (!replyTarget.value || !replyText.value.trim()) {
    message.warning('请输入回复内容')
    return
  }
  replying.value = true
  try {
    await reviewAdminApi.reply(replyTarget.value.id, replyText.value.trim())
    message.success('回复成功')
    replyVisible.value = false
    await loadList()
  } finally {
    replying.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; }
.toolbar-left { display: flex; align-items: center; gap: 10px; }
.table-card {
  background: var(--ds-surface, #fff);
  border-radius: var(--ds-r-lg, 16px);
  padding: 16px;
}
.table-footer { display: flex; justify-content: flex-end; margin-top: 16px; }
.row-actions { display: flex; gap: 2px; }
.product-cell { display: flex; align-items: center; }
.dialog-tip { margin: 0 0 8px; color: var(--ds-text-2, #5b6472); }
.tip-strong { font-weight: 700; }
.quote {
  margin: 0 0 12px;
  padding: 8px 12px;
  background: var(--ds-bg-2, #f5f6f8);
  border-radius: 8px;
  color: var(--ds-text-2, #5b6472);
}
</style>
