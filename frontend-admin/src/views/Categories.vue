<template>
  <div class="page fade-in">
    <div class="toolbar">
      <div class="toolbar-title">分类管理</div>
      <n-button type="primary" @click="openAdd">新增分类</n-button>
    </div>

    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :scroll-x="880"
      />
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <n-modal
      v-model:show="dialogVisible"
      preset="card"
      :title="editingId ? '编辑分类' : '新增分类'"
      style="width: 520px"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="left" label-width="90">
        <n-form-item label="分类名称" path="name">
          <n-input v-model:value="form.name" placeholder="如：手机数码 / 智能手机" maxlength="50" />
        </n-form-item>
        <n-form-item label="父分类">
          <n-select
            v-model:value="form.parentId"
            :options="parentOptions"
            placeholder="不选即为一级分类"
          />
        </n-form-item>
        <n-form-item label="排序">
          <n-input-number v-model:value="form.sort" :min="0" style="width: 160px" />
        </n-form-item>
        <n-form-item label="图标 URL">
          <n-input v-model:value="form.icon" placeholder="可选，分类图标地址" maxlength="500" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="form.status" :options="statusOptions" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="dialogVisible = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NButton, NDataTable, NForm, NFormItem, NInput, NInputNumber,
  NSelect, NTag, useMessage, useDialog,
  type DataTableColumns, type FormInst, type FormRules
} from 'naive-ui'
import { categoryApi, type CategoryVO, type CategoryPayload } from '@/api/catalog'

const message = useMessage()
const dialog = useDialog()

const list = ref<CategoryVO[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInst | null>(null)

const form = reactive({
  name: '',
  parentId: '0',
  sort: 0,
  icon: '',
  status: 0
})

const rules: FormRules = {
  name: { required: true, message: '请输入分类名称', trigger: ['blur', 'input'] }
}

const statusOptions = [
  { label: '正常', value: 0 },
  { label: '停用', value: 1 }
]

/** 一级分类选项（父分类只能是一级分类，分类最多两级） */
const parentOptions = computed(() => [
  { label: '无（作为一级分类）', value: '0' },
  ...list.value
    .filter(c => c.parentId === '0' && c.id !== editingId.value)
    .map(c => ({ label: c.name, value: c.id }))
])

const parentNameOf = (id: string) => {
  if (!id || id === '0') return '—'
  return list.value.find(c => c.id === id)?.name || '—'
}

const rowKey = (row: CategoryVO) => row.id

const columns: DataTableColumns<CategoryVO> = [
  { title: '分类名称', key: 'name', minWidth: 180 },
  {
    title: '层级',
    key: 'level',
    width: 90,
    render: row => h(NTag, { size: 'small', type: row.parentId === '0' ? 'info' : 'default' },
      { default: () => (row.parentId === '0' ? '一级' : '二级') })
  },
  {
    title: '父分类',
    key: 'parentName',
    width: 140,
    render: row => parentNameOf(row.parentId)
  },
  { title: '排序', key: 'sort', width: 80 },
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
      h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => handleDelete(row) },
        { default: () => '删除' })
    ])
  }
]

async function loadList() {
  loading.value = true
  try {
    const res = await categoryApi.list()
    list.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openAdd() {
  editingId.value = null
  Object.assign(form, { name: '', parentId: '0', sort: 0, icon: '', status: 0 })
  dialogVisible.value = true
}

function openEdit(row: CategoryVO) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    parentId: row.parentId || '0',
    sort: row.sort ?? 0,
    icon: row.icon || '',
    status: row.status ?? 0
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
    const payload: CategoryPayload = {
      name: form.name,
      parentId: form.parentId,
      sort: form.sort,
      icon: form.icon || undefined,
      status: form.status
    }
    if (editingId.value) {
      await categoryApi.update(editingId.value, payload)
    } else {
      await categoryApi.add(payload)
    }
    message.success(editingId.value ? '分类已更新' : '分类已新增')
    dialogVisible.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

function handleDelete(row: CategoryVO) {
  dialog.warning({
    title: '删除分类',
    content: `确定删除「${row.name}」吗？有子分类或商品引用时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await categoryApi.remove(row.id)
      message.success('分类已删除')
      await loadList()
    }
  })
}

onMounted(loadList)
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.toolbar-title { font-size: 18px; font-weight: 700; }
.table-card {
  background: var(--ds-surface, #fff);
  border-radius: var(--ds-r-lg, 16px);
  padding: 16px;
}
.row-actions { display: flex; gap: 4px; }
</style>
