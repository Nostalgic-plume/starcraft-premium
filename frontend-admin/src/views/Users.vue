<template>
  <div class="page fade-in">
    <!-- 工具条：关键词搜索 + 状态筛选 -->
    <div class="toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="搜索用户名 / 昵称 / 手机号"
        clearable
        style="width: 260px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <n-select
        v-model:value="statusFilter"
        :options="statusOptions"
        placeholder="状态筛选"
        clearable
        style="width: 140px"
        @update:value="handleFilterChange"
      />
      <n-button type="primary" @click="handleSearch">搜索</n-button>
      <n-button quaternary @click="handleReset">重置</n-button>
    </div>

    <!-- 用户表格 -->
    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :checked-row-keys="checkedRowKeys"
        :scroll-x="1000"
        @update:checked-row-keys="handleCheckChange"
      />

      <!-- 底部：批量操作 + 分页 -->
      <div class="table-footer">
        <div class="footer-left">
          <n-button
            v-if="checkedRowKeys.length > 0"
            size="small"
            type="primary"
            secondary
            @click="openBatchDialog"
          >
            批量设置状态
          </n-button>
          <span v-if="checkedRowKeys.length > 0" class="selected-tip">
            已选 {{ checkedRowKeys.length }} 项
          </span>
        </div>
        <n-pagination
          v-if="total > 0"
          v-model:page="page"
          :page-size="pageSize"
          :item-count="total"
          :page-slot="5"
          @update:page="loadUsers"
        />
      </div>
    </div>

    <!-- 状态变更弹窗（单条 / 批量共用） -->
    <n-modal
      v-model:show="dialogVisible"
      preset="card"
      :title="dialogMode === 'batch' ? '批量设置用户状态' : '设置用户状态'"
      style="width: 440px"
      :mask-closable="false"
    >
      <p v-if="dialogMode === 'batch'" class="dialog-tip">
        已选择 <span class="tip-strong">{{ checkedRowKeys.length }}</span> 位用户
      </p>
      <p v-else-if="currentUser" class="dialog-tip">
        用户：<span class="tip-strong">{{ currentUser.username }}</span>（{{ currentUser.nickname }}）
      </p>
      <n-form :model="dialogForm" label-placement="left" label-width="90">
        <n-form-item label="目标状态">
          <n-select
            v-model:value="dialogForm.status"
            :options="statusOptions"
            placeholder="请选择目标状态"
          />
        </n-form-item>
        <n-form-item label="原因说明">
          <n-input
            v-model:value="dialogForm.reason"
            type="textarea"
            :rows="3"
            placeholder="请输入状态变更原因（可选）"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="dialogVisible = false">取消</n-button>
        <n-button type="primary" :loading="submitting" @click="submitModify">确定</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import { NButton, NDataTable, NInput, NPagination, NSelect, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { adminApi } from '@/api/admin'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'
import type { UserPageQuery, UserVO } from '@/api/types'

const message = useMessage()

/* ==================== 数据状态 ==================== */

const list = ref<UserVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

/** 关键词：匹配用户名 / 昵称 / 手机号（后端 like） */
const keyword = ref('')
/** 状态筛选：null = 全部 */
const statusFilter = ref<number | null>(null)

/** 表格勾选的行 id */
const checkedRowKeys = ref<string[]>([])

/** 用户状态选项（后端约定：0正常 1停用 2注销中 3封禁中） */
const statusOptions = [
  { label: '正常', value: 0 },
  { label: '停用', value: 1 },
  { label: '注销中', value: 2 },
  { label: '封禁中', value: 3 }
]

/** 用户状态 → StatusTag 语义色：0正常绿 / 1停用、2注销灰 / 3封禁红 */
function userStatusType(status: number): StatusType {
  switch (status) {
    case 0: return 'completed'
    case 1: return 'disabled'
    case 2: return 'cancelled'
    case 3: return 'failed'
    default: return 'disabled'
  }
}

const statusTextMap: Record<number, string> = { 0: '正常', 1: '停用', 2: '注销中', 3: '封禁中' }

/* ==================== 状态变更弹窗 ==================== */

const dialogVisible = ref(false)
const dialogMode = ref<'single' | 'batch'>('single')
const dialogForm = ref<{ status: number | null; reason: string }>({ status: 0, reason: '' })
const currentUser = ref<UserVO | null>(null)
const submitting = ref(false)

function openModifyDialog(row: UserVO, targetStatus: number) {
  dialogMode.value = 'single'
  currentUser.value = row
  dialogForm.value = { status: targetStatus, reason: '' }
  dialogVisible.value = true
}

function openBatchDialog() {
  if (checkedRowKeys.value.length === 0) {
    message.warning('请先勾选用户')
    return
  }
  dialogMode.value = 'batch'
  currentUser.value = null
  dialogForm.value = { status: 0, reason: '' }
  dialogVisible.value = true
}

async function submitModify() {
  if (dialogForm.value.status === null || dialogForm.value.status === undefined) {
    message.warning('请选择目标状态')
    return
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'batch') {
      await adminApi.batchUpdateUserStatus({
        ids: checkedRowKeys.value,
        status: dialogForm.value.status,
        reason: dialogForm.value.reason
      })
      message.success(`已批量修改 ${checkedRowKeys.value.length} 位用户状态`)
      checkedRowKeys.value = []
    } else {
      const target = currentUser.value
      if (!target) return
      await adminApi.updateUserStatus(target.id, {
        status: dialogForm.value.status,
        reason: dialogForm.value.reason
      })
      message.success(`用户 ${target.username} 状态已修改`)
    }
    dialogVisible.value = false
    loadUsers()
  } catch (e) {
    console.error('修改用户状态失败:', e)
    message.error('操作失败')
  } finally {
    submitting.value = false
  }
}

/* ==================== 表格 ==================== */

function rowKey(row: UserVO) {
  return row.id
}

function handleCheckChange(keys: (string | number)[]) {
  checkedRowKeys.value = keys.map((k) => String(k))
}

const columns: DataTableColumns<UserVO> = [
  { type: 'selection', width: 50 },
  {
    title: '用户名',
    key: 'username',
    width: 120,
    render: (row) => h('span', { class: 'cell-mono' }, row.username || '-')
  },
  { title: '昵称', key: 'nickname', width: 120, render: (row) => h('span', {}, row.nickname || '-') },
  { title: '手机号', key: 'phone', width: 140, render: (row) => h('span', {}, row.phone || '-') },
  {
    title: '状态',
    key: 'status',
    width: 100,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: userStatusType(row.status),
        label: statusTextMap[row.status] || '未知'
      })
  },
  {
    title: '注册时间',
    key: 'createTime',
    width: 170,
    render: (row) => h('span', { class: 'cell-muted' }, row.createTime || '-')
  },
  {
    title: '操作',
    key: 'actions',
    width: 180,
    align: 'center',
    fixed: 'right',
    render: (row) => {
      const btns = []
      if (row.status !== 0) {
        btns.push(
          h(
            NButton,
            { size: 'small', quaternary: true, type: 'primary', onClick: () => openModifyDialog(row, 0) },
            { default: () => '启用' }
          )
        )
      }
      if (row.status !== 1) {
        btns.push(
          h(
            NButton,
            { size: 'small', quaternary: true, type: 'warning', onClick: () => openModifyDialog(row, 1) },
            { default: () => '停用' }
          )
        )
      }
      if (row.status !== 3) {
        btns.push(
          h(
            NButton,
            { size: 'small', quaternary: true, type: 'error', onClick: () => openModifyDialog(row, 3) },
            { default: () => '封禁' }
          )
        )
      }
      return h('div', { class: 'action-btns' }, btns)
    }
  }
]

/* ==================== 数据加载 ==================== */

async function loadUsers() {
  loading.value = true
  try {
    const params: UserPageQuery = { page: page.value, pageSize: pageSize.value }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (statusFilter.value !== null) {
      params.status = statusFilter.value
    }
    const res = await adminApi.pageUsers(params)
    list.value = res.data?.list || []
    total.value = Number(res.data?.total) || 0
  } catch (e) {
    console.error('加载用户列表失败:', e)
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadUsers()
}

function handleFilterChange() {
  page.value = 1
  loadUsers()
}

function handleReset() {
  keyword.value = ''
  statusFilter.value = null
  page.value = 1
  loadUsers()
}

onMounted(loadUsers)
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
.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
}
.footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.selected-tip {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
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
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
.tip-strong {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}

/* ====== 单元格 ====== */
.table-card :deep(.cell-muted) {
  color: var(--ds-text-3, #98a1af);
}
.table-card :deep(.cell-mono) {
  font-family: var(--ds-mono, Consolas, monospace);
}
</style>
