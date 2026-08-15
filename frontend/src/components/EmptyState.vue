<script setup lang="ts">
import { computed } from 'vue'
import SvgIcon from './SvgIcon.vue'

const props = withDefaults(defineProps<{
  /** 空状态文案 */
  text?: string
  /** 次要说明 */
  description?: string
  /** 图标名（默认开箱插画） */
  icon?: string
}>(), {
  text: '暂无数据',
  description: '',
  icon: 'box-open'
})

/** 大尺寸插画图标（放大并加粗内圆角） */
const iconSize = computed(() => 72)
</script>

<template>
  <div class="empty-state">
    <div class="empty-illustration">
      <svg viewBox="0 0 100 100" class="empty-bg" aria-hidden="true">
        <circle cx="50" cy="50" r="44" fill="rgba(45,127,249,0.06)" />
      </svg>
      <div class="empty-icon">
        <SvgIcon :name="icon" :size="iconSize" />
      </div>
    </div>
    <p class="empty-text">{{ text }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <div v-if="$slots.default" class="empty-actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 56px 24px;
  text-align: center;
}
.empty-illustration {
  position: relative;
  width: 120px;
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}
.empty-bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.empty-icon {
  position: relative;
  color: var(--ds-primary, #2d7ff9);
  opacity: 0.7;
}
.empty-text {
  font-size: 15px;
  font-weight: 600;
  color: var(--ds-text-2, #5a6474);
  margin: 0 0 6px;
}
.empty-desc {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}
.empty-actions {
  margin-top: 20px;
}
</style>
