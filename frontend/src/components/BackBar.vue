<script setup lang="ts">
import { useRouter } from 'vue-router'
import SvgIcon from './SvgIcon.vue'

const props = withDefaults(defineProps<{
  /** 按钮文字 */
  label?: string
  /** 指定跳转地址；为空时回退到历史后退，无历史则回首页 */
  to?: string
}>(), {
  label: '返回',
  to: ''
})

const router = useRouter()

function goBack() {
  if (props.to) {
    router.push(props.to)
  } else if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/home')
  }
}
</script>

<template>
  <div class="back-bar">
    <button class="back-btn" type="button" @click="goBack">
      <SvgIcon name="back" :size="18" />
      <span>{{ label }}</span>
    </button>
    <slot />
  </div>
</template>

<style scoped>
.back-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  margin-bottom: 8px;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px 6px 10px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  background: var(--ds-card, #ffffff);
  color: var(--ds-text-2, #5a6474);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}
.back-btn:hover {
  color: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
}
</style>
