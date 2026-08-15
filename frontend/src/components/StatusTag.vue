<script setup lang="ts">
import { computed } from 'vue'

export type StatusType =
  | 'on-sale'       // 在售
  | 'paid'          // 已支付
  | 'pending-ship'  // 待发货
  | 'pending-pay'   // 待支付
  | 'completed'     // 已完成
  | 'cancelled'     // 已取消
  | 'disabled'      // 已停用 / 已下架

const props = withDefaults(defineProps<{
  /** 语义类型，决定配色 */
  type?: StatusType
  /** 覆盖默认文案（如后端 statusText） */
  label?: string
}>(), {
  type: 'paid'
})

interface StatusConfig {
  label: string
  color: string
  bg: string
}

/** 语义色：蓝 #2D7FF9 / 橙 #E08600 / 绿 #12A77D / 灰 #98A1AF / 红 #E0404E */
const configs: Record<StatusType, StatusConfig> = {
  'on-sale': { label: '在售', color: '#2D7FF9', bg: 'rgba(45,127,249,0.08)' },
  paid: { label: '已支付', color: '#2D7FF9', bg: 'rgba(45,127,249,0.08)' },
  'pending-ship': { label: '待发货', color: '#E08600', bg: 'rgba(224,134,0,0.10)' },
  'pending-pay': { label: '待支付', color: '#E08600', bg: 'rgba(224,134,0,0.10)' },
  completed: { label: '已完成', color: '#12A77D', bg: 'rgba(18,167,125,0.10)' },
  cancelled: { label: '已取消', color: '#98A1AF', bg: 'rgba(152,161,175,0.14)' },
  disabled: { label: '已停用', color: '#98A1AF', bg: 'rgba(152,161,175,0.14)' }
}

const cfg = computed(() => configs[props.type])
const display = computed(() => props.label || cfg.value.label)
</script>

<template>
  <span
    class="ds-status-tag"
    :style="{ color: cfg.color, background: cfg.bg, borderColor: 'transparent' }"
  >
    <span class="dot" :style="{ background: cfg.color }" />
    {{ display }}
  </span>
</template>

<style scoped>
.ds-status-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: var(--ds-r-pill, 999px);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.6;
  white-space: nowrap;
  user-select: none;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
