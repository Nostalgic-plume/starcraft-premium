<template>
  <div class="rec-rules-page fade-in">
    <n-card class="card" :bordered="true">
      <template #header>
        <div class="card-header">
          <span class="card-title">过滤规则</span>
          <n-space :size="10">
            <n-button size="small" :loading="loading" @click="load">刷新</n-button>
            <n-button type="primary" size="small" @click="openCreate">新增规则</n-button>
          </n-space>
        </div>
      </template>

      <n-alert class="tip" type="warning" :bordered="false" title="当前生效范围（请勿按未生效的能力配置）">
        <p class="tip-line">
          <strong>黑名单</strong>：已接入过滤链路，配置后立即生效（规则变更会重建内存过滤清单，
          下一次请求即读到最新规则）。
        </p>
        <p class="tip-line">
          <strong>地域不可售 / 会员限定 / 低质商品</strong>：过滤判断已接入链路，但其所依赖的业务数据
          （地域、会员等级、质量分）商城侧暂未提供，<strong>当前不会产生实际过滤效果</strong>，仅作为规则通路保留。
        </p>
        <p class="tip-line">
          <strong>仅支持按商品标识排除</strong>：虽然库表保留了「类目」这一作用对象，
          但过滤时一律以商品标识匹配，配置类目不会排除该类别下的商品。
        </p>
      </n-alert>

      <n-spin :show="loading">
        <n-data-table
          :columns="columns"
          :data="rules"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: RecFilterRule) => String(row.id)"
        />
      </n-spin>
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingId ? '编辑过滤规则' : '新增过滤规则'"
      :style="{ width: '520px' }"
    >
      <n-form ref="formRef" :model="form" :rules="rules2" label-placement="left" label-width="90">
        <n-form-item label="规则类型" path="ruleType">
          <n-select v-model:value="form.ruleType" :options="ruleTypeOptions" placeholder="选择规则类型" />
        </n-form-item>
        <n-form-item label="作用对象">
          <n-select v-model:value="form.targetType" :options="targetTypeOptions" />
        </n-form-item>
        <n-form-item label="对象取值" path="targetValue">
          <n-input v-model:value="form.targetValue" placeholder="商品标识（长整型，原样粘贴）" />
        </n-form-item>
        <n-form-item label="状态">
          <n-switch v-model:value="statusOn">
            <template #checked>启用</template>
            <template #unchecked>停用</template>
          </n-switch>
        </n-form-item>
        <n-form-item label="规则说明">
          <n-input
            v-model:value="form.remark"
            type="textarea"
            :rows="2"
            placeholder="记录配置原因，便于后续追溯"
          />
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
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace,
  NSpin,
  NSwitch,
  NTag,
  useMessage
} from 'naive-ui'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import { recAdminApi } from '@/api/rec'
import type { RecFilterRule } from '@/api/types'

const message = useMessage()

const loading = ref(false)
const submitting = ref(false)
const rules = ref<RecFilterRule[]>([])
const showModal = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInst | null>(null)

/** 规则类型选项；与后端 FusionFilterService 的常量取值一一对应 */
const RULE_TYPE_LABELS: Record<string, string> = {
  blacklist: '黑名单',
  region_limit: '地域不可售',
  member_only: '会员限定',
  low_quality: '低质商品'
}

const ruleTypeOptions = Object.entries(RULE_TYPE_LABELS).map(([value, label]) => ({ label, value }))

/** 作用对象：仅商品维度真实生效，类目维度后端未实现，故不作为选项提供 */
const targetTypeOptions = [{ label: '商品（当前唯一生效维度）', value: 'item' }]

function emptyForm(): RecFilterRule {
  return { ruleType: 'blacklist', targetType: 'item', targetValue: '', status: 1, remark: '' }
}

const form = ref<RecFilterRule>(emptyForm())

const statusOn = computed({
  get: () => form.value.status === 1,
  set: (on: boolean) => {
    form.value.status = on ? 1 : 0
  }
})

const rules2: FormRules = {
  ruleType: { required: true, message: '请选择规则类型', trigger: ['blur', 'change'] },
  targetValue: { required: true, message: '请填写对象取值', trigger: 'blur' }
}

const columns: DataTableColumns<RecFilterRule> = [
  {
    title: '规则类型',
    key: 'ruleType',
    width: 130,
    render: row =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.ruleType === 'blacklist' ? 'error' : 'default' },
        { default: () => RULE_TYPE_LABELS[row.ruleType] || row.ruleType }
      )
  },
  {
    title: '对象取值',
    key: 'targetValue',
    width: 200,
    render: row => h('span', { class: 'mono' }, row.targetValue)
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
  { title: '规则说明', key: 'remark', ellipsis: { tooltip: true } },
  { title: '维护人', key: 'operator', width: 110 },
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
              default: () => `确认删除规则「${RULE_TYPE_LABELS[row.ruleType] || row.ruleType} / ${row.targetValue}」？`
            }
          )
        ]
      })
  }
]

async function load() {
  loading.value = true
  try {
    const res = await recAdminApi.listRules()
    rules.value = res.data || []
  } catch (e) {
    console.error('加载过滤规则失败:', e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(row: RecFilterRule) {
  editingId.value = String(row.id)
  // 历史数据里可能存有 category，统一回落到商品维度：类目维度后端不生效，避免继续沿用
  form.value = { ...row, targetType: 'item' }
  showModal.value = true
}

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    if (editingId.value) {
      await recAdminApi.updateRule({ ...form.value, id: editingId.value })
    } else {
      await recAdminApi.createRule(form.value)
    }
    message.success('保存成功，过滤清单已重建')
    showModal.value = false
    await load()
  } catch (e) {
    console.error('保存过滤规则失败:', e)
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: RecFilterRule) {
  try {
    await recAdminApi.deleteRule(String(row.id))
    message.success('已删除')
    await load()
  } catch (e) {
    console.error('删除过滤规则失败:', e)
  }
}

onMounted(load)
</script>

<style scoped>
.rec-rules-page {
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

.tip-line {
  margin: 0 0 6px;
  line-height: 1.6;
}

.tip-line:last-child {
  margin-bottom: 0;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
</style>
