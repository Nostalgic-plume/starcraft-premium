<template>
  <div class="page-container">
    <!-- 搜索与批量操作栏 -->
    <div class="card-elevated mb-16">
      <div class="flex-between" style="flex-wrap: wrap; gap: 12px">
        <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
          <el-input
            v-model="keyword"
            placeholder="搜索用户名/昵称/手机号"
            clearable
            style="width: 260px"
            @keyup.enter="handleSearch"
          />
          <el-button type="primary" @click="handleSearch">搜索</el-button>
        </div>
        <div>
          <el-button
            type="warning"
            :disabled="selectedIds.length === 0"
            @click="openBatchDialog"
          >
            批量修改状态
          </el-button>
          <span v-if="selectedIds.length > 0" style="margin-left: 8px; font-size: 13px; color: var(--ds-text-muted, #999)">
            已选 {{ selectedIds.length }} 项
          </span>
        </div>
      </div>
    </div>

    <div class="card-elevated">
      <!-- 状态筛选标签 -->
      <el-tabs v-model="statusFilter" @tab-change="handleTabChange" class="mb-16">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="正常" name="0" />
        <el-tab-pane label="停用" name="1" />
        <el-tab-pane label="注销中" name="2" />
        <el-tab-pane label="封禁中" name="3" />
      </el-tabs>

      <!-- 用户表格 -->
      <el-table
        :data="list"
        v-loading="loading"
        stripe
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="nickname" label="昵称" width="120" show-overflow-tooltip />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip />
        <el-table-column label="管理员级别" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.adminLevel === 2" type="danger" size="small" effect="dark">超级管理员</el-tag>
            <el-tag v-else-if="row.adminLevel === 1" type="primary" size="small" effect="dark">普通管理员</el-tag>
            <span v-else style="color: var(--ds-text-muted, #999); font-size: 12px">非管理员</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTypeMap[row.status] || 'info'" size="small" effect="dark">
              {{ statusTextMap[row.status] || '未知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openModifyDialog(row)">
              修改状态
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" description="暂无用户数据" />

      <!-- 分页 -->
      <div v-if="total > 0" class="flex-center mt-16">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadUsers"
        />
      </div>
    </div>

    <!-- 修改状态对话框（单条/批量共用） -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'batch' ? '批量修改用户状态' : '修改用户状态'"
      width="440px"
      :close-on-click-modal="false"
    >
      <p v-if="dialogMode === 'batch'" style="margin-bottom: 16px; color: var(--ds-text-muted, #666)">
        已选择 <span style="color: var(--ds-coral, #e94560); font-weight: 600">{{ selectedIds.length }}</span> 位用户
      </p>
      <p v-else-if="currentUser" style="margin-bottom: 16px; color: var(--ds-text-muted, #666)">
        用户: <strong>{{ currentUser.username }}</strong>（{{ currentUser.nickname }}）
      </p>
      <el-form :model="dialogForm" label-width="80px">
        <el-form-item label="目标状态" required>
          <el-select v-model="dialogForm.status" placeholder="请选择状态" style="width: 100%">
            <el-option label="正常" :value="0" />
            <el-option label="停用" :value="1" />
            <el-option label="注销中" :value="2" />
            <el-option label="封禁中" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因说明">
          <el-input
            v-model="dialogForm.reason"
            type="textarea"
            :rows="3"
            placeholder="请输入状态变更原因（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitModify">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/admin'

const list = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref('')
const selectedIds = ref([])

// 状态映射
const statusTextMap = { 0: '正常', 1: '停用', 2: '注销中', 3: '封禁中' }
const statusTypeMap = { 0: 'success', 1: 'warning', 2: 'info', 3: 'danger' }

// 对话框
const dialogVisible = ref(false)
const dialogMode = ref('single')
const dialogForm = ref({ status: 0, reason: '' })
const currentUser = ref(null)
const submitting = ref(false)

// 表格选择变更
function handleSelectionChange(selection) {
  selectedIds.value = selection.map(s => s.id)
}

// 加载用户列表
async function loadUsers() {
  loading.value = true
  try {
    const params = { page: page.value, pageSize: pageSize.value }
    if (keyword.value) params.keyword = keyword.value
    if (statusFilter.value !== '') params.status = parseInt(statusFilter.value)
    const res = await adminApi.pageUsers(params)
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
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

function handleTabChange() {
  page.value = 1
  loadUsers()
}

// 打开单条修改对话框
function openModifyDialog(row) {
  dialogMode.value = 'single'
  currentUser.value = row
  dialogForm.value = { status: row.status ?? 0, reason: '' }
  dialogVisible.value = true
}

// 打开批量修改对话框
function openBatchDialog() {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请先选择用户')
    return
  }
  dialogMode.value = 'batch'
  currentUser.value = null
  dialogForm.value = { status: 0, reason: '' }
  dialogVisible.value = true
}

// 提交状态修改
async function submitModify() {
  if (dialogForm.value.status === undefined || dialogForm.value.status === null) {
    ElMessage.warning('请选择目标状态')
    return
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'batch') {
      await adminApi.batchUpdateUserStatus({
        ids: selectedIds.value,
        status: dialogForm.value.status,
        reason: dialogForm.value.reason
      })
      ElMessage.success(`已批量修改 ${selectedIds.value.length} 位用户状态`)
      selectedIds.value = []
    } else {
      await adminApi.updateUserStatus(currentUser.value.id, {
        status: dialogForm.value.status,
        reason: dialogForm.value.reason
      })
      ElMessage.success(`用户 ${currentUser.value.username} 状态已修改`)
    }
    dialogVisible.value = false
    loadUsers()
  } catch (e) {
    console.error('修改用户状态失败:', e)
    ElMessage.error('操作失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadUsers)
</script>

<style scoped>
.page-container {
  height: 100%;
  max-width: none;
  display: flex;
  flex-direction: column;
}
.page-container > .card-elevated:last-child {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
:deep(.el-tabs) { flex-shrink: 0; }
:deep(.el-table) {
  flex: 1;
  min-height: 0;
}

/* Table header & row hover */
:deep(.el-table) {
  --el-table-header-bg-color: var(--ds-bg, #f5f3ef);
  --el-table-row-hover-bg-color: var(--ds-bg, #f5f3ef);
}

/* Dialog title */
:deep(.el-dialog__title) {
  color: var(--ds-navy, #1a1a2e);
}
</style>

