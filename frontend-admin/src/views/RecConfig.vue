<template>
  <div class="rec-config-page fade-in">
    <n-card class="card" :bordered="true">
      <template #header>
        <div class="card-header">
          <span class="card-title">推荐策略配置</span>
          <n-space :size="10" align="center">
            <n-select
              v-model:value="groupName"
              class="group-select"
              size="small"
              :options="groupOptions"
              @update:value="load"
            />
            <n-button size="small" :loading="loading" @click="load">刷新</n-button>
            <n-button
              type="primary"
              size="small"
              :disabled="changedCount === 0"
              :loading="saving"
              @click="handleSave"
            >
              保存修改{{ changedCount ? `（${changedCount}）` : '' }}
            </n-button>
          </n-space>
        </div>
      </template>

      <n-alert class="tip" type="info" :bordered="false" title="改动约 30 秒内生效">
        配置保存后落库并同步到内存副本，各模块每 30 秒比对一次版本号，<strong>无需重启服务</strong>。
        校验不通过的配置会被拒绝写入，库中保留原值。
      </n-alert>

      <n-alert
        v-if="hasForceDegrade"
        class="tip"
        type="error"
        :bordered="false"
        title="注意：强制降级开关已出现在当前列表中"
      >
        <code>degrade.force</code> 会把<strong>全站推荐</strong>置为指定降级状态（用于演练与应急处置）。
        非应急场景请勿修改，改完请及时置回空值。
      </n-alert>

      <n-spin :show="loading">
        <n-data-table
          :columns="columns"
          :data="configs"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: RecConfigItem) => row.configKey"
          :max-height="560"
          virtual-scroll
        />
      </n-spin>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NInput,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { recAdminApi } from '@/api/rec'
import type { RecConfigItem } from '@/api/types'

const message = useMessage()

const loading = ref(false)
const saving = ref(false)
const configs = ref<RecConfigItem[]>([])
const groupName = ref<string | null>(null)

/**
 * 编辑草稿：只记录被改动过的配置键 → 新值
 *
 * 未改动的键不进入草稿，因此「保存」时提交的天然就是最小变更集，
 * 不会把整张表回写一遍（避免并发编辑时互相覆盖）。
 */
const draft = reactive<Record<string, string>>({})

/** 分组选项：从返回数据中提取，避免在前端硬编码分组枚举 */
const groupOptions = computed(() => {
  const groups = Array.from(new Set(configs.value.map(c => c.groupName).filter(Boolean)))
  return [{ label: '全部分组', value: '' }, ...groups.map(g => ({ label: g, value: g }))]
})

const changedCount = computed(() => Object.keys(draft).length)

/** 强制降级开关是否在当前列表中 */
const hasForceDegrade = computed(() => configs.value.some(c => c.configKey === 'degrade.force'))

/** 取当前生效值：草稿优先 */
function currentValue(row: RecConfigItem): string {
  return draft[row.configKey] ?? row.configValue
}

/** 输入变更：值与库中一致时移出草稿，避免「改回原值」仍被计入待保存 */
function onValueChange(row: RecConfigItem, value: string) {
  if (value === row.configValue) {
    delete draft[row.configKey]
  } else {
    draft[row.configKey] = value
  }
}

const columns: DataTableColumns<RecConfigItem> = [
  {
    title: '配置键',
    key: 'configKey',
    width: 230,
    render: row => h('span', { class: 'mono' }, row.configKey)
  },
  {
    title: '配置值',
    key: 'configValue',
    width: 210,
    render: row =>
      h(NInput, {
        value: currentValue(row),
        size: 'small',
        // 改动未保存时标黄，避免误以为已生效
        status: draft[row.configKey] === undefined ? undefined : 'warning',
        onUpdateValue: (value: string) => onValueChange(row, value)
      })
  },
  {
    title: '类型',
    key: 'valueType',
    width: 90,
    render: row => h(NTag, { size: 'small', bordered: false }, { default: () => row.valueType })
  },
  { title: '分组', key: 'groupName', width: 110 },
  { title: '说明', key: 'remark', ellipsis: { tooltip: true } },
  { title: '修改人', key: 'operator', width: 100 },
  { title: '修改时间', key: 'updateTime', width: 170 }
]

async function load() {
  loading.value = true
  try {
    const res = await recAdminApi.listConfigs(groupName.value || undefined)
    configs.value = res.data || []
    // 重新拉取后草稿即失效：库里已是最新值
    Object.keys(draft).forEach(key => delete draft[key])
  } catch (e) {
    console.error('加载推荐策略配置失败:', e)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  const items = Object.entries(draft).map(([configKey, configValue]) => ({
    configKey,
    configValue,
    // 后端在 valueType 缺省时沿用库中已有类型，此处显式带上以减少歧义
    valueType: configs.value.find(c => c.configKey === configKey)?.valueType || undefined
  }))
  if (items.length === 0) {
    return
  }
  saving.value = true
  try {
    const res = await recAdminApi.updateConfigs(items)
    message.success(`配置已生效，版本号 ${res.data}`)
    await load()
  } catch (e) {
    console.error('保存推荐策略配置失败:', e)
    // 具体失败原因（如校验不通过）由响应拦截器统一提示
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.rec-config-page {
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

.group-select {
  width: 150px;
}

.tip {
  margin-bottom: 14px;
  border-radius: var(--ds-r, 12px);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--ds-text, #1e2632);
}
</style>
