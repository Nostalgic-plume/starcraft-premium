<template>
  <div class="rag-index-page fade-in">
    <n-card class="card" :bordered="true">
      <template #header>
        <div class="card-header">
          <span class="card-title">索引状态</span>
          <n-button size="small" :loading="statusLoading" @click="loadStatus">刷新状态</n-button>
        </div>
      </template>

      <n-spin :show="statusLoading">
        <n-descriptions v-if="status" label-placement="left" :column="1" bordered>
          <n-descriptions-item label="已索引商品数">
            <n-tag type="success" :bordered="false">{{ status.indexedCount }}</n-tag>
          </n-descriptions-item>
          <n-descriptions-item label="最近重建时间">
            <span class="mono">{{ status.lastRebuildTime }}</span>
            <span class="hint">（仅本次后端进程内有效，重启后显示「未重建」）</span>
          </n-descriptions-item>
          <n-descriptions-item label="最近重建结果">
            <n-tag :type="rebuildState.type" :bordered="false">{{ rebuildState.label }}</n-tag>
            <span class="hint">{{ status.lastRebuildMessage }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="FULLTEXT 索引">
            <n-tag :type="status.fulltextIndex.healthy ? 'success' : 'error'" :bordered="false">
              {{ status.fulltextIndex.healthy ? '正常' : '异常' }}
            </n-tag>
            <span class="hint">{{ status.fulltextIndex.message }}</span>
          </n-descriptions-item>
        </n-descriptions>
        <n-empty v-else description="暂无数据，请点击「刷新状态」" />
      </n-spin>

      <n-alert class="tip" type="warning" :bordered="false" title="重建前请确认嵌入服务已就绪">
        重建会<strong>先删除 Qdrant 集合</strong>，再按当前配置（维度 / 嵌入模型）全量重写；
        集合删除到重新灌完之间<strong>向量检索不可用</strong>，若嵌入服务此时不可用则数据补不回来。
        <br />
        另注意：重启后端<strong>不会</strong>自动重建（启动钩子见集合已有数据即跳过），
        因此切换嵌入模型导致维度变更后，必须在此手动触发一次。
      </n-alert>

      <div class="actions">
        <n-button type="error" secondary :loading="rebuilding" @click="handleRebuild">
          重建索引
        </n-button>
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NSpin,
  NTag,
  useDialog,
  useMessage
} from 'naive-ui'
import { ragAdminApi } from '@/api/rag'
import type { RagIndexStatus } from '@/api/types'

const dialog = useDialog()
const message = useMessage()

const status = ref<RagIndexStatus | null>(null)
const statusLoading = ref(false)
const rebuilding = ref(false)

/**
 * 重建结果标签：区分「未重建」与「重建失败」
 * 后端起进程内状态，初始 message 为「尚未执行重建」—— 此时 lastRebuildSuccess 也是 false，
 * 直接按布尔渲染会把「从未重建」显示成「失败」，属误报。
 */
const rebuildState = computed(() => {
  if (!status.value || status.value.lastRebuildMessage === '尚未执行重建') {
    return { type: 'default' as const, label: '未重建' }
  }
  return status.value.lastRebuildSuccess
    ? { type: 'success' as const, label: '成功' }
    : { type: 'error' as const, label: '失败' }
})

let timer: number | undefined

/** 停止轮询（完成 / 超时 / 组件卸载均走此处） */
function stopPolling() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
  rebuilding.value = false
}

onUnmounted(stopPolling)

async function loadStatus() {
  statusLoading.value = true
  try {
    const res = await ragAdminApi.status()
    status.value = (res.data as RagIndexStatus) || null
  } catch (e) {
    console.error('加载索引状态失败:', e)
  } finally {
    statusLoading.value = false
  }
}

/**
 * 以 lastRebuildTime 变化作为「动作结束」信号，再按 lastRebuildSuccess 判断成败
 * 用时间戳判断结束比「猜条数」稳：重建期间条数会先掉到 0 再回升，单看条数无法判断是否结束。
 * ⚠️ 但时间戳**只代表动作已结束**：后端重建首步会先删掉 Qdrant 集合，
 * 若随后向量化失败（如嵌入服务未就绪）而提前返回，集合已空却同样更新了时间戳 ——
 * 只按时间戳弹「重建完成」会把「索引为空 + 向量路不可用」显示成成功。
 */
function startPolling(baseline: string) {
  let tries = 0
  timer = window.setInterval(async () => {
    await loadStatus()
    if (status.value?.lastRebuildTime !== baseline) {
      stopPolling()
      const detail = status.value?.lastRebuildMessage
      if (status.value?.lastRebuildSuccess) {
        message.success(String(detail || '索引重建完成'))
      } else {
        message.error(String(detail || '索引重建失败，请检查向量化服务（TEI）与后端日志'))
      }
      return
    }
    if (++tries >= 60) {
      stopPolling()
      message.warning('等待超时（已等约 120 秒），请手动刷新或查看后端日志')
    }
  }, 2000)
}

function handleRebuild() {
  dialog.warning({
    title: '确认重建索引',
    content:
      '重建会先删除 Qdrant 集合，再按当前配置（维度 / 嵌入模型）全量重写，期间向量检索不可用。请先确认嵌入服务已就绪。',
    positiveText: '确认重建',
    negativeText: '取消',
    onPositiveClick: async () => {
      const baseline = status.value?.lastRebuildTime ?? ''
      try {
        await ragAdminApi.rebuild()
        message.success('已触发重建，正在等待完成…')
        rebuilding.value = true
        startPolling(baseline)
      } catch (e) {
        console.error('触发重建失败:', e)
        message.error('触发失败')
      }
    }
  })
}

onMounted(loadStatus)
</script>

<style scoped>
.rag-index-page {
  max-width: 860px;
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

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--ds-text, #1e2632);
}

.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

.tip {
  margin-top: 20px;
  border-radius: var(--ds-r, 12px);
}

.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
