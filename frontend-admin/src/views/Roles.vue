<template>
  <div class="page fade-in">
    <div class="toolbar">
      <div class="toolbar-title">角色权限</div>
      <div class="toolbar-right">
        <n-button quaternary @click="openAssignUser">分配用户角色</n-button>
        <n-button type="primary" @click="openAdd">新增角色</n-button>
      </div>
    </div>

    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :scroll-x="900"
      />
    </div>

    <!-- 角色新增 / 编辑 -->
    <n-modal
      v-model:show="dialogVisible"
      preset="card"
      :title="editingId ? '编辑角色' : '新增角色'"
      style="width: 620px"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="left" label-width="90">
        <n-form-item label="角色名称" path="name">
          <n-input v-model:value="form.name" placeholder="如：运营管理员" maxlength="50" />
        </n-form-item>
        <n-form-item label="角色编码" path="code">
          <n-input
            v-model:value="form.code"
            :disabled="!!editingId"
            placeholder="唯一编码，创建后不可修改，如：operator"
            maxlength="50"
          />
        </n-form-item>
        <n-form-item label="描述">
          <n-input
            v-model:value="form.description"
            type="textarea"
            :rows="2"
            placeholder="可选"
            maxlength="200"
          />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="form.status" :options="statusOptions" style="width: 160px" />
        </n-form-item>
        <n-form-item label="权限">
          <n-checkbox-group v-model:value="form.permissionIds">
            <div class="perm-grid">
              <n-checkbox
                v-for="perm in permissions"
                :key="perm.id"
                :value="perm.id"
                :label="perm.name"
              />
            </div>
          </n-checkbox-group>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="dialogVisible = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
      </template>
    </n-modal>

    <!-- 分配用户角色 -->
    <n-modal
      v-model:show="assignVisible"
      preset="card"
      title="分配用户角色"
      style="width: 480px"
      :mask-closable="false"
    >
      <n-form label-placement="left" label-width="90">
        <n-form-item label="用户 ID">
          <n-input v-model:value="assignUserId" placeholder="输入用户 ID" />
        </n-form-item>
        <n-form-item label="角色">
          <n-select
            v-model:value="assignRoleIds"
            multiple
            :options="roleOptions"
            placeholder="选择要分配的角色（覆盖式）"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="assignVisible = false">取消</n-button>
        <n-button type="primary" :loading="assigning" @click="handleAssignUserRoles">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NButton, NCheckbox, NCheckboxGroup, NDataTable, NForm, NFormItem, NInput,
  NSelect, NTag, useDialog, useMessage,
  type DataTableColumns, type FormInst, type FormRules
} from 'naive-ui'
import { roleApi, type RoleVO, type PermissionVO, type RolePayload } from '@/api/role'

const message = useMessage()
const dialog = useDialog()

const list = ref<RoleVO[]>([])
const permissions = ref<PermissionVO[]>([])
const loading = ref(false)
const saving = ref(false)
const assigning = ref(false)
const dialogVisible = ref(false)
const assignVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInst | null>(null)
const assignUserId = ref('')
const assignRoleIds = ref<string[]>([])

/** 内置角色由后端启动初始化，不允许删除 */
const BUILTIN_CODES = ['admin', 'merchant', 'user']

const form = reactive({
  name: '',
  code: '',
  description: '',
  status: 0,
  permissionIds: [] as string[]
})

const rules: FormRules = {
  name: { required: true, message: '请输入角色名称', trigger: ['blur', 'input'] },
  code: { required: true, message: '请输入角色编码', trigger: ['blur', 'input'] }
}

const statusOptions = [
  { label: '正常', value: 0 },
  { label: '停用', value: 1 }
]

const roleOptions = computed(() => list.value.map(r => ({ label: `${r.name}（${r.code}）`, value: r.id })))

const rowKey = (row: RoleVO) => row.id

const columns: DataTableColumns<RoleVO> = [
  { title: '角色名称', key: 'name', minWidth: 140 },
  { title: '编码', key: 'code', width: 130 },
  { title: '描述', key: 'description', minWidth: 200, ellipsis: { tooltip: true } },
  {
    title: '权限数',
    key: 'permissionIds',
    width: 90,
    render: row => String(row.permissionIds?.length || 0)
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: row => h(NTag, { size: 'small', type: row.status === 0 ? 'success' : 'warning' },
      { default: () => (row.status === 0 ? '正常' : '停用') })
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    render: row => h('div', { class: 'row-actions' }, [
      h(NButton, { size: 'small', quaternary: true, onClick: () => openEdit(row) },
        { default: () => '编辑' }),
      h(NButton, {
        size: 'small',
        quaternary: true,
        type: 'error',
        disabled: BUILTIN_CODES.includes(row.code),
        onClick: () => handleDelete(row)
      }, { default: () => '删除' })
    ])
  }
]

async function loadAll() {
  loading.value = true
  try {
    const [roles, perms] = await Promise.all([roleApi.list(), roleApi.permissions()])
    list.value = roles.data || []
    permissions.value = perms.data || []
  } finally {
    loading.value = false
  }
}

function openAdd() {
  editingId.value = null
  Object.assign(form, { name: '', code: '', description: '', status: 0, permissionIds: [] })
  dialogVisible.value = true
}

function openEdit(row: RoleVO) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    code: row.code,
    description: row.description || '',
    status: row.status ?? 0,
    permissionIds: [...(row.permissionIds || [])]
  })
  dialogVisible.value = true
}

async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const payload: RolePayload = {
      name: form.name,
      code: form.code,
      description: form.description || undefined,
      status: form.status,
      permissionIds: form.permissionIds
    }
    if (editingId.value) {
      await roleApi.update(editingId.value, payload)
    } else {
      await roleApi.add(payload)
    }
    message.success(editingId.value ? '角色已更新' : '角色已新增')
    dialogVisible.value = false
    await loadAll()
  } finally {
    saving.value = false
  }
}

function handleDelete(row: RoleVO) {
  dialog.warning({
    title: '删除角色',
    content: `确定删除「${row.name}」吗？仍有用户关联时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await roleApi.remove(row.id)
      message.success('角色已删除')
      await loadAll()
    }
  })
}

function openAssignUser() {
  assignUserId.value = ''
  assignRoleIds.value = []
  assignVisible.value = true
}

async function handleAssignUserRoles() {
  if (!assignUserId.value.trim()) {
    message.warning('请输入用户 ID')
    return
  }
  assigning.value = true
  try {
    await roleApi.assignUserRoles(assignUserId.value.trim(), assignRoleIds.value)
    message.success('用户角色已更新')
    assignVisible.value = false
  } finally {
    assigning.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.toolbar-title { font-size: 18px; font-weight: 700; }
.toolbar-right { display: flex; gap: 10px; }
.table-card {
  background: var(--ds-surface, #fff);
  border-radius: var(--ds-r-lg, 16px);
  padding: 16px;
}
.perm-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 16px;
}
.row-actions { display: flex; gap: 4px; }
</style>
