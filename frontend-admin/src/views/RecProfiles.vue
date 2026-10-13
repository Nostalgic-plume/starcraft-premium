<template>
  <div class="rec-profiles-page fade-in">
    <n-card class="card" :bordered="true">
      <template #header>
        <div class="card-header">
          <span class="card-title">用户画像版本</span>
        </div>
      </template>

      <n-alert class="tip" type="info" :bordered="false" title="画像按天生成，保留最近 3 个版本">
        画像每日凌晨为活跃用户重新生成并落新版本，<strong>旧版本保留、可回滚</strong>
        （标签「当前生效」为正在提供偏好的版本）。回滚后下一个推荐请求即按目标版本取用偏好数据，
        无需重启；超出保留范围的版本会被拒绝（错误码 4004），当前版本保持不变。
      </n-alert>

      <n-space class="search-bar" :size="10">
        <n-input
          v-model:value="userId"
          class="user-input"
          placeholder="用户标识（长整型，原样粘贴）"
          @keyup.enter="load"
        />
        <n-button type="primary" size="small" :loading="loading" @click="load">查询</n-button>
      </n-space>

      <n-spin :show="loading">
        <div v-if="versions.length" class="version-list">
          <div v-for="v in versions" :key="v.id" class="version-item">
            <div class="version-head">
              <div class="version-title">
                <span class="version-no">{{ v.version }}</span>
                <n-tag
                  size="small"
                  :bordered="false"
                  :type="v.status === 0 ? 'success' : 'default'"
                >
                  {{ v.status === 0 ? '当前生效' : '历史版本' }}
                </n-tag>
                <span class="version-meta">
                  归纳行为 {{ v.behaviorCount }} 条 · 生成于 {{ v.createTime }}
                </span>
              </div>
              <n-button
                v-if="v.status !== 0"
                size="tiny"
                type="warning"
                secondary
                @click="handleRollback(v)"
              >
                回滚到此版本
              </n-button>
            </div>

            <div class="pref-row">
              <span class="pref-label">类目偏好</span>
              <template v-if="v.categoryPref?.length">
                <n-tag
                  v-for="p in v.categoryPref"
                  :key="p.dimId"
                  size="small"
                  :bordered="false"
                  class="pref-tag"
                >
                  类目 {{ p.dimId }} · {{ formatScore(p.score) }}
                </n-tag>
              </template>
              <span v-else class="pref-empty">无</span>
            </div>

            <div class="pref-row">
              <span class="pref-label">品牌偏好</span>
              <template v-if="v.brandPref?.length">
                <n-tag
                  v-for="p in v.brandPref"
                  :key="p.dimId"
                  size="small"
                  :bordered="false"
                  class="pref-tag"
                >
                  品牌 {{ p.dimId }} · {{ formatScore(p.score) }}
                </n-tag>
              </template>
              <span v-else class="pref-empty">无</span>
            </div>
          </div>
        </div>

        <n-empty
          v-else-if="searched"
          description="该用户暂无画像版本"
        >
          <template #extra>
            <span class="empty-hint">
              行为条数少于 3 条的用户视为冷启动，不生成画像；也可能该用户标识不存在。
            </span>
          </template>
        </n-empty>

        <n-empty v-else description="输入用户标识后查询" />
      </n-spin>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NEmpty,
  NInput,
  NSpace,
  NSpin,
  NTag,
  useDialog,
  useMessage
} from 'naive-ui'
import { recAdminApi } from '@/api/rec'
import type { RecProfileVersion } from '@/api/types'

const dialog = useDialog()
const message = useMessage()

const userId = ref('')
const loading = ref(false)
/** 是否已执行过查询：用于区分「还没查」与「查了但没有版本」 */
const searched = ref(false)
const versions = ref<RecProfileVersion[]>([])

/** 偏好分展示：保留两位，去掉无意义的小数尾零 */
function formatScore(score: number): string {
  const n = Number(score)
  return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : '-'
}

async function load() {
  const id = userId.value.trim()
  if (!id) {
    message.warning('请先输入用户标识')
    return
  }
  loading.value = true
  try {
    const res = await recAdminApi.listProfileVersions(id)
    versions.value = res.data || []
    searched.value = true
  } catch (e) {
    console.error('查询画像版本失败:', e)
    versions.value = []
    searched.value = true
  } finally {
    loading.value = false
  }
}

function handleRollback(version: RecProfileVersion) {
  const id = userId.value.trim()
  dialog.warning({
    title: `确认回滚到版本 ${version.version}？`,
    content:
      `将把用户 ${id} 的画像切回 ${version.version}（归纳行为 ${version.behaviorCount} 条），` +
      '下一次推荐请求即按该版本提供偏好数据。当前生效版本会转为历史版本，不会被删除。',
    positiveText: '确认回滚',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await recAdminApi.rollbackProfile(id, version.version, '管理后台手动回滚')
        message.success(`已回滚到版本 ${version.version}`)
        await load()
      } catch (e) {
        console.error('回滚画像版本失败:', e)
        // 目标版本不可用（4004）等原因由响应拦截器统一提示
      }
    }
  })
}
</script>

<style scoped>
.rec-profiles-page {
  max-width: 1080px;
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

.search-bar {
  margin-bottom: 16px;
}

.user-input {
  width: 320px;
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.version-item {
  padding: 14px 16px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  background: var(--ds-card, #fff);
}

.version-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.version-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.version-no {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 15px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
}

.version-meta {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

.pref-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.pref-label {
  flex-shrink: 0;
  width: 68px;
  font-size: 12px;
  color: var(--ds-text-2, #5b6472);
}

.pref-tag {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.pref-empty {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

.empty-hint {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
</style>
