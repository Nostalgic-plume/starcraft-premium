<template>
  <div class="page fade-in">
    <div class="toolbar">
      <div class="toolbar-left">
        <n-input
          v-model:value="keyword"
          placeholder="搜索品牌名称"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <n-button type="primary" @click="handleSearch">搜索</n-button>
      </div>
      <n-button type="primary" @click="openAdd">新增品牌</n-button>
    </div>

    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :scroll-x="900"
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

    <n-modal
      v-model:show="dialogVisible"
      preset="card"
      :title="editingId ? '编辑品牌' : '新增品牌'"
      style="width: 520px"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="left" label-width="90">
        <n-form-item label="品牌名称" path="name">
          <n-input v-model:value="form.name" placeholder="如：华为" maxlength="100" />
        </n-form-item>
        <n-form-item label="Logo URL">
          <n-input v-model:value="form.logo" placeholder="可选，品牌 Logo 地址" maxlength="500" />
        </n-form-item>
        <n-form-item label="品牌描述">
          <n-input
            v-model:value="form.description"
            type="textarea"
            :rows="3"
            placeholder="可选"
            maxlength="500"
          />
        </n-form-item>
        <n-form-item label="排序">
          <n-input-number v-model:value="form.sort" :min="0" style="width: 160px" />
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
import { h, onMounted, reactive, ref } from 'vue'
import {
  NButton, NDataTable, NForm, NFormItem, NInput, NInputNumber,
  NPagination, NSelect, NTag, useDialog, useMessage,
  type DataTableColumns, type FormInst, type FormRules
} from 'naive-ui'
import { brandApi, type BrandVO, type BrandPayload } from '@/api/catalog'

const message = useMessage()
const dialog = useDialog()

const list = ref<BrandVO[]>([])
const loading = ref(false)
const saving = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const keyword = ref('')
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInst | null>(null)

const form = reactive({
  name: '',
  logo: '',
  description: '',
  sort: 0,
  status: 0
})

const rules: FormRules = {
  name: { required: true, message: '请输入品牌名称', trigger: ['blur', 'input'] }
}

const statusOptions = [
  { label: '正常', value: 0 },
  { label: '停用', value: 1 }
]

const rowKey = (row: BrandVO) => row.id

const columns: DataTableColumns<BrandVO> = [
  {
    title: 'Logo',
    key: 'logo',
    width: 80,
    render: row => row.logo
      ? h('img', { src: row.logo, alt: row.name, style: 'width:36px;height:36px;object-fit:contain;border-radius:6px' })
      : h('span', { style: 'color:#98a1af' }, '—')
  },
  { title: '品牌名称', key: 'name', minWidth: 160 },
  { title: '描述', key: 'description', minWidth: 220, ellipsis: { tooltip: true } },
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
    const res = await brandApi.page({
      page: page.value,
      pageSize: pageSize.value,
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

function openAdd() {
  editingId.value = null
  Object.assign(form, { name: '', logo: '', description: '', sort: 0, status: 0 })
  dialogVisible.value = true
}

function openEdit(row: BrandVO) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    logo: row.logo || '',
    description: row.description || '',
    sort: row.sort ?? 0,
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
    const payload: BrandPayload = {
      name: form.name,
      logo: form.logo || undefined,
      description: form.description || undefined,
      sort: form.sort,
      status: form.status
    }
    if (editingId.value) {
      await brandApi.update(editingId.value, payload)
    } else {
      await brandApi.add(payload)
    }
    message.success(editingId.value ? '品牌已更新' : '品牌已新增')
    dialogVisible.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

function handleDelete(row: BrandVO) {
  dialog.warning({
    title: '删除品牌',
    content: `确定删除「${row.name}」吗？有商品引用时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await brandApi.remove(row.id)
      message.success('品牌已删除')
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
.toolbar-left { display: flex; align-items: center; gap: 10px; }
.table-card {
  background: var(--ds-surface, #fff);
  border-radius: var(--ds-r-lg, 16px);
  padding: 16px;
}
.table-footer { display: flex; justify-content: flex-end; margin-top: 16px; }
.row-actions { display: flex; gap: 4px; }
</style>
