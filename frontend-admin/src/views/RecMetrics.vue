<template>
  <div class="rec-metrics-page fade-in">
    <n-card class="card" :bordered="true">
      <template #header>
        <div class="card-header">
          <span class="card-title">运行看板</span>
          <n-space :size="10" align="center">
            <n-select
              v-model:value="hours"
              class="range-select"
              size="small"
              :options="rangeOptions"
              @update:value="load"
            />
            <n-button size="small" :loading="loading" @click="load">刷新</n-button>
          </n-space>
        </div>
      </template>

      <n-alert class="tip" type="info" :bordered="false" title="数值口径">
        指标由推荐链路各环节实时上报，按统计时段聚合。卡片展示所选区间内
        <strong>最近一个统计时段</strong>的值：计数类求和，比率与耗时类取平均
        （聚合方式以后端指标定义为准，不在前端重复判断）。
        <strong>标红</strong>表示已越过预警阈值；未设阈值的指标只展示数值。
      </n-alert>

      <n-spin :show="loading">
        <div v-if="catalog.length" class="metric-grid">
          <div
            v-for="def in catalog"
            :key="def.name"
            class="metric-card"
            :class="{ 'metric-card--alarming': isAlarming(def) }"
          >
            <div class="metric-head">
              <span class="metric-label">{{ def.label }}</span>
              <n-tag v-if="isAlarming(def)" size="tiny" type="error" :bordered="false">超阈值</n-tag>
            </div>

            <div class="metric-value">
              <span class="value">{{ displayValue(def) }}</span>
              <span class="unit">{{ def.unit }}</span>
            </div>

            <div class="metric-threshold">{{ thresholdText(def) }}</div>

            <div v-if="trend(def).length" class="spark">
              <div
                v-for="(point, i) in trend(def)"
                :key="i"
                class="spark-bar"
                :style="{ height: barHeight(def, point.value) }"
                :title="`${point.time} · ${point.value}`"
              />
            </div>

            <div class="metric-time">
              {{ latestOf(def) ? '统计时段 ' + latestOf(def)!.time : '暂无数据' }}
            </div>
          </div>
        </div>
        <n-empty v-else description="暂无指标定义，请确认推荐服务已启动" />
      </n-spin>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NAlert, NButton, NCard, NEmpty, NSelect, NSpace, NSpin, NTag, useMessage } from 'naive-ui'
import { recAdminApi } from '@/api/rec'
import type { RecMetricDefinition, RecMetricRecord } from '@/api/types'

const message = useMessage()

/** 趋势条最多的展示点数 */
const MAX_TREND_POINTS = 12

const loading = ref(false)
const hours = ref(24)
const catalog = ref<RecMetricDefinition[]>([])
const records = ref<RecMetricRecord[]>([])

const rangeOptions = [
  { label: '最近 6 小时', value: 6 },
  { label: '最近 24 小时', value: 24 },
  { label: '最近 3 天', value: 72 },
  { label: '最近 7 天', value: 168 }
]

/** 指标名 → 按统计时段聚合后的序列（时间升序，末尾为最新） */
const series = computed(() => {
  // 先按「指标名 → 统计时段 → 该时段内各维度取值」三层归集
  const byMetric = new Map<string, Map<string, number[]>>()
  for (const record of records.value) {
    if (!byMetric.has(record.metricName)) {
      byMetric.set(record.metricName, new Map())
    }
    const byTime = byMetric.get(record.metricName)!
    if (!byTime.has(record.statTime)) {
      byTime.set(record.statTime, [])
    }
    byTime.get(record.statTime)!.push(Number(record.metricValue) || 0)
  }

  const result = new Map<string, Array<{ time: string; value: number }>>()
  for (const [metricName, byTime] of byMetric) {
    const definition = catalog.value.find(d => d.name === metricName)
    // 缺定义时保守按求和：宁可显示偏大，也不要对比率类做未定义的换算
    const aggregation = definition?.aggregation ?? 'sum'
    const points = Array.from(byTime.entries()).map(([time, values]) => {
      const total = values.reduce((sum, v) => sum + v, 0)
      return { time, value: aggregation === 'avg' ? total / values.length : total }
    })
    points.sort((a, b) => a.time.localeCompare(b.time))
    result.set(metricName, points)
  }
  return result
})

/** 取某指标最近的展示点 */
function latestOf(def: RecMetricDefinition) {
  const points = series.value.get(def.name)
  return points?.length ? points[points.length - 1] : null
}

/** 趋势条：仅取最近若干点 */
function trend(def: RecMetricDefinition) {
  const points = series.value.get(def.name) || []
  return points.slice(-MAX_TREND_POINTS).map(p => ({ ...p, time: shortTime(p.time) }))
}

/** 数值展示：比率保留一位小数，其余取整 */
function displayValue(def: RecMetricDefinition): string {
  const latest = latestOf(def)
  if (!latest) {
    return '—'
  }
  return def.unit === '%' ? latest.value.toFixed(1) : String(Math.round(latest.value))
}

/** 是否越过预警阈值 */
function isAlarming(def: RecMetricDefinition): boolean {
  const latest = latestOf(def)
  if (!latest || def.threshold == null) {
    return false
  }
  return def.higherIsBetter ? latest.value < def.threshold : latest.value > def.threshold
}

function thresholdText(def: RecMetricDefinition): string {
  if (def.threshold == null) {
    return '未设预警阈值'
  }
  return `预警阈值 ${def.higherIsBetter ? '≥' : '≤'} ${def.threshold}${def.unit}`
}

/** 条形高度：相对该指标自身的最大值，保证不同量级的指标都能看出形状 */
function barHeight(def: RecMetricDefinition, value: number): string {
  const points = series.value.get(def.name) || []
  const max = Math.max(...points.map(p => p.value), 1)
  return `${Math.max(6, Math.round((value / max) * 38))}px`
}

/** 时间取到分钟：完整时间戳放在 title 里，卡片上不必占位 */
function shortTime(statTime: string): string {
  const matched = /\d{2}:\d{2}/.exec(statTime || '')
  return matched ? matched[0] : statTime
}

async function load() {
  loading.value = true
  try {
    // 指标定义变化极少，仅在首次加载时拉取
    const [catalogRes, recordsRes] = await Promise.all([
      catalog.value.length ? Promise.resolve(null) : recAdminApi.metricCatalog(),
      recAdminApi.listMetrics({ hours: hours.value, limit: 500 })
    ])
    if (catalogRes) {
      catalog.value = catalogRes.data || []
    }
    records.value = recordsRes.data || []
    if (!records.value.length) {
      message.info('所选区间内暂无指标数据')
    }
  } catch (e) {
    console.error('加载运行指标失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.rec-metrics-page {
  max-width: 1320px;
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

.range-select {
  width: 140px;
}

.tip {
  margin-bottom: 16px;
  border-radius: var(--ds-r, 12px);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(232px, 1fr));
  gap: 14px;
}

.metric-card {
  padding: 14px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  background: var(--ds-card, #fff);
  transition: border-color 0.2s ease;
}

.metric-card--alarming {
  border-color: var(--ds-red, #e0404e);
  background: rgba(224, 64, 78, 0.03);
}

.metric-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.metric-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text-2, #5b6472);
}

.metric-value {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-bottom: 4px;
}

.value {
  font-size: 26px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.5px;
  color: var(--ds-text, #1e2632);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.metric-card--alarming .value {
  color: var(--ds-red, #e0404e);
}

.unit {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

.metric-threshold {
  font-size: 11px;
  color: var(--ds-text-3, #98a1af);
  margin-bottom: 10px;
}

.spark {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 40px;
  padding-bottom: 2px;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}

.spark-bar {
  flex: 1;
  min-width: 3px;
  border-radius: 2px 2px 0 0;
  background: var(--ds-primary, #2d7ff9);
  opacity: 0.75;
  transition: height 0.2s ease;
}

.metric-card--alarming .spark-bar {
  background: var(--ds-red, #e0404e);
}

.metric-time {
  margin-top: 8px;
  font-size: 11px;
  color: var(--ds-text-3, #98a1af);
}
</style>
