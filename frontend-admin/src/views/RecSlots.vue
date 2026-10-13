<template>
  <div class="rec-slots-page fade-in">
    <n-card class="card" :bordered="true">
      <template #header>
        <div class="card-header">
          <span class="card-title">固定坑位</span>
          <n-space :size="10">
            <n-button size="small" :loading="loading" @click="load">刷新</n-button>
            <n-button type="primary" size="small" @click="openCreate">新增坑位</n-button>
          </n-space>
        </div>
      </template>

      <n-alert class="tip" type="info" :bordered="false" title="坑位是「钉死在该位置」的商品">
        坑位在榜单的指定位置上强制展示指定商品，优先级高于召回与打分结果。
        <strong>同一位置只允许一个启用中的坑位</strong>，位置冲突时后端会拒绝写入。
        仅在生效时间段内、且状态为启用时才会作用于榜单。
      </n-alert>

      <n-spin :show="loading">
        <n-data-table
          :columns="columns"
          :data="slots"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: RecFixedSlot) => String(row.id)"
        />
      </n-spin>
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      class="slot-modal"
      :title="editingId ? '编辑坑位' : '新增坑位'"
      :style="{ width: '520px' }"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="left" label-width="90">
        <n-form-item label="坑位位置" path="slotPosition">
          <n-input-number
            v-model:value="form.slotPosition"
            class="w-full"
            :min="1"
            placeholder="从 1 开始的榜单序号"
          />
        </n-form-item>
        <n-form-item label="商品标识" path="itemId">
          <n-input v-model:value="form.itemId" placeholder="商品 ID（长整型，原样粘贴）" />
        </n-form-item>
        <n-form-item label="生效开始">
          <n-date-picker
            v-model:formatted-value="form.startTime"
            class="w-full"
            type="datetime"
            clearable
            value-format="yyyy-MM-dd HH:mm:ss"
            placeholder="留空表示立即生效"
          />
        </n-form-item>
        <n-form-item label="生效结束">
          <n-date-picker
            v-model:formatted-value="form.endTime"
            class="w-full"
            type="datetime"
            clearable
            value-format="yyyy-MM-dd HH:mm:ss"
            placeholder="留空表示长期有效"
          />
        </n-form-item>
        <n-form-item label="状态">
          <n-switch v-model:value="statusOn">
            <template #checked>启用</template>
            <template #unchecked>停用</template>
          </n-switch>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end" :size="10">
          <n-button size="small" @click="showModal = false">取消</n-button>
          <n-button type="primary" size="small" :loading="submitting" @click="handleSubmit">
            保存
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDatePicker,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSpace,
  NSpin,
  NSwitch,
  NTag,
  useMessage
} from 'naive-ui'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import { recAdminApi } from '@/api/rec'
import type { RecFixedSlot } from '@/api/types'

const message = useMessage()

const loading = ref(false)
const submitting = ref(false)
const slots = ref<RecFixedSlot[]>([])
const showModal = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInst | null>(null)

function emptyForm(): RecFixedSlot {
  return { slotPosition: null, itemId: null, startTime: null, endTime: null, status: 1 }
}

const form = ref<RecFixedSlot>(emptyForm())

/** 状态开关：库中存 0/1，界面上用布尔更直观 */
const statusOn = computed({
  get: () => form.value.status === 1,
  set: (on: boolean) => {
    form.value.status = on ? 1 : 0
  }
})

const rules: FormRules = {
  slotPosition: {
    required: true,
    type: 'number',
    message: '请填写坑位位置',
    trigger: ['blur', 'change']
  },
  itemId: { required: true, message: '请填写商品标识', trigger: 'blur' }
}

const columns: DataTableColumns<RecFixedSlot> = [
  { title: '位置', key: 'slotPosition', width: 80 },
  {
    title: '商品标识',
    key: 'itemId',
    width: 200,
    render: row => h('span', { class: 'mono' }, String(row.itemId ?? ''))
  },
  {
    title: '生效时间',
    key: 'time',
    render: row => h('span', { class: 'time' }, `${row.startTime || '立即'} ~ ${row.endTime || '长期'}`)
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: row =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 1 ? 'success' : 'default' },
        { default: () => (row.status === 1 ? '启用' : '停用') }
      )
  },
  { title: '配置人', key: 'operator', width: 110 },
  { title: '更新时间', key: 'updateTime', width: 170 },
  {
    title: '操作',
    key: 'actions',
    width: 130,
    render: row =>
      h(NSpace, { size: 6 }, {
        default: () => [
          h(
            NButton,
            { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openEdit(row) },
            { default: () => '编辑' }
          ),
          h(
            NPopconfirm,
            { onPositiveClick: () => handleDelete(row) },
            {
              trigger: () =>
                h(
                  NButton,
                  { size: 'tiny', quaternary: true, type: 'error' },
                  { default: () => '删除' }
                ),
              default: () => `确认删除位置 ${row.slotPosition} 的坑位？`
            }
          )
        ]
      })
  }
]

async function load() {
  loading.value = true
  try {
    const res = await recAdminApi.listSlots()
    slots.value = res.data || []
  } catch (e) {
    console.error('加载固定坑位失败:', e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(row: RecFixedSlot) {
  editingId.value = String(row.id)
  form.value = { ...row }
  showModal.value = true
}

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    // 校验未通过：NForm 已在字段下方给出提示
    return
  }
  submitting.value = true
  try {
    if (editingId.value) {
      await recAdminApi.updateSlot({ ...form.value, id: editingId.value })
    } else {
      await recAdminApi.createSlot(form.value)
    }
    message.success('保存成功')
    showModal.value = false
    await load()
  } catch (e) {
    console.error('保存固定坑位失败:', e)
    // 位置冲突等业务错误由响应拦截器统一提示
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: RecFixedSlot) {
  try {
    await recAdminApi.deleteSlot(String(row.id))
    message.success('已删除')
    await load()
  } catch (e) {
    console.error('删除固定坑位失败:', e)
  }
}

onMounted(load)
</script>

<style scoped>
.rec-slots-page {
  max-width: 1280px;
  margin: 0 auto;
}

.card {
  border-radius: var(--ds-r-lg, 16px);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
}

.tip {
  margin-bottom: 14px;
  border-radius: var(--ds-r, 12px);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.time {
  font-size: 12px;
  color: var(--ds-text-2, #5b6472);
}

.w-full {
  width: 100%;
}
</style>
