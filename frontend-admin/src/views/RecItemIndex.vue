<template>
  <div class="rec-item-index-page fade-in">
    <n-card class="card" :bordered="true">
      <template #header>
        <div class="card-header">
          <span class="card-title">商品索引状态</span>
          <n-space :size="10">
            <n-button size="small" :loading="statusLoading" @click="loadStatus">刷新状态</n-button>
            <n-button
              type="primary"
              size="small"
              :loading="triggering"
              :disabled="rebuilding"
              @click="handleRebuild"
            >
              重建索引
            </n-button>
          </n-space>
        </div>
      </template>

      <n-spin :show="statusLoading">
        <n-descriptions v-if="status" label-placement="left" :column="1" bordered>
          <n-descriptions-item label="当前状态">
            <n-tag :type="status.rebuilding ? 'warning' : 'success'" :bordered="false">
              {{ status.rebuilding ? '重建中…' : '空闲' }}
            </n-tag>
          </n-descriptions-item>
          <n-descriptions-item label="最近重建时间">
            <span class="mono">{{ status.lastRebuildTime || '从未重建' }}</span>
            <span class="hint">（仅本次后端进程内有效，服务重启后回到「从未重建」）</span>
          </n-descriptions-item>
          <n-descriptions-item label="最近重建结果">
            <n-tag :type="stateTag.type" :bordered="false">{{ stateTag.label }}</n-tag>
            <span class="hint">{{ status.lastRebuildMessage }}</span>
          </n-descriptions-item>
        </n-descriptions>
        <n-empty v-else description="暂无数据，请点击「刷新状态」" />
      </n-spin>

      <n-alert class="tip" type="warning" :bordered="false" title="重建期间榜单会短暂降级">
        重建分四步：清空已失效的商品状态缓存 → 全量重写可售商品状态 → 重建类目候选索引 →
        重建兜底名单。<strong>清理与重写之间存在空窗期</strong>，期间的请求可能因查不到商品状态
        而被过滤，榜单回落到兜底名单。属低频运维操作，影响可接受。
      </n-alert>

      <n-alert class="tip" type="info" :bordered="false" title="什么时候需要手动重建">
        <p class="tip-line">
          商品索引平时由定时任务维护（全量每日 01:00、类目索引与兜底名单每 10 分钟刷新），
          <strong>日常增删改单个商品无需手动重建</strong>。
        </p>
        <p class="tip-line">
          但<strong>批量替换商品数据后必须手动重建一次</strong>：已被删除的商品不会出现在分页遍历结果里，
          其状态缓存既不会被写入覆盖、也不会被对账任务清理，会残留成「已不存在但仍显示在售」的悬空商品。
          重建会先按模式清空状态缓存再全量重写，正是为了解决这一点。
        </p>
      </n-alert>
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
  NSpace,
  NSpin,
  NTag,
  useDialog,
  useMessage
} from 'naive-ui'
import { recAdminApi } from '@/api/rec'
import type { RecItemRebuildStatus } from '@/api/types'

/** 与后端 ItemDataService.NOT_REBUILT 一致：表示「从未重建」而非「失败」 */
const NOT_REBUILT = '尚未执行重建'

/** 轮询间隔（毫秒） */
const POLL_INTERVAL = 1500

/** 轮询次数上限（约 2 分钟） */
const MAX_POLL_TRIES = 80

const dialog = useDialog()
const message = useMessage()

const status = ref<RecItemRebuildStatus | null>(null)
const statusLoading = ref(false)
/** 触发请求进行中（区别于重建本身进行中） */
const triggering = ref(false)
/** 重建进行中（用于禁用按钮） */
const rebuilding = ref(false)

/**
 * 重建结果标签：区分「未重建」与「重建失败」
 *
 * 后端起进程内状态，初始说明为「尚未执行重建」——此时 lastRebuildSuccess 也是 false，
 * 直接按布尔渲染会把「从未重建」显示成「失败」，属误报。
 */
const stateTag = computed(() => {
  if (!status.value || status.value.lastRebuildMessage === NOT_REBUILT) {
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
  triggering.value = false
}
onUnmounted(stopPolling)

async function loadStatus() {
  statusLoading.value = true
  try {
    const res = await recAdminApi.itemRebuildStatus()
    status.value = res.data || null
    rebuilding.value = !!status.value?.rebuilding
  } catch (e) {
    console.error('加载商品索引状态失败:', e)
  } finally {
    statusLoading.value = false
  }
}

/**
 * 以 rebuilding 由 true 变为 false 作为「本轮重建结束」的信号
 *
 * 比看时间戳更直接：重建是异步的，触发后接口立即返回，时间戳要等重建真正跑完才会更新。
 */
function startPolling() {
  let tries = 0
  timer = window.setInterval(async () => {
    await loadStatus()
    if (!status.value?.rebuilding) {
      stopPolling()
      if (status.value?.lastRebuildSuccess) {
        message.success(status.value.lastRebuildMessage || '商品索引重建完成')
      } else {
        message.error(status.value?.lastRebuildMessage || '商品索引重建失败，请查看后端日志')
      }
      return
    }
    if (++tries >= MAX_POLL_TRIES) {
      stopPolling()
      message.warning('等待超时（已等约 2 分钟），请手动刷新状态或查看后端日志')
    }
  }, POLL_INTERVAL)
}

function handleRebuild() {
  dialog.warning({
    title: '确认重建商品索引',
    content:
      '重建会清空并重写商品状态缓存、重建类目候选索引与兜底名单。期间榜单可能短暂降级到兜底名单。是否继续？',
    positiveText: '确认重建',
    negativeText: '取消',
    onPositiveClick: async () => {
      triggering.value = true
      try {
        await recAdminApi.triggerItemRebuild()
        message.success('已触发重建，正在等待完成…')
        startPolling()
      } catch (e) {
        console.error('触发商品索引重建失败:', e)
        // 已有任务在执行中（4005）等原因由响应拦截器统一提示；刷新一次状态让用户看到实况
        triggering.value = false
        await loadStatus()
      }
    }
  })
}

onMounted(loadStatus)
</script>

<style scoped>
.rec-item-index-page {
  max-width: 880px;
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
  margin-top: 18px;
  border-radius: var(--ds-r, 12px);
}

.tip-line {
  margin: 0 0 6px;
  line-height: 1.6;
}

.tip-line:last-child {
  margin-bottom: 0;
}
</style>
