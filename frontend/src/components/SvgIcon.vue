<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  /** 图标名（见下方 icons 注册表） */
  name: string
  /** 宽高，单位 px（也接受字符串） */
  size?: number | string
}>(), {
  size: 18
})

/**
 * 内联 SVG 图标注册表（24×24，stroke 线性风格）
 * 统一继承 currentColor，圆角端点，stroke-width 1.7
 */
const icons: Record<string, string> = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/>',
  cart: '<circle cx="9" cy="20" r="1.4"/><circle cx="18" cy="20" r="1.4"/><path d="M2.5 3.5H5l2.2 11.5a1.6 1.6 0 0 0 1.6 1.3h8.9a1.6 1.6 0 0 0 1.6-1.3L21.5 7H7"/>',
  orders: '<rect x="4" y="3.5" width="16" height="17" rx="2"/><path d="M8.5 8h7M8.5 12h7M8.5 16h4"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5c1.5-3.2 4.2-4.8 7.5-4.8s6 1.6 7.5 4.8"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-3.8-3.8"/>',
  back: '<path d="M14.5 5 8 12l6.5 7"/><path d="M8.5 12H20"/>',
  'chevron-right': '<path d="m9 5 7 7-7 7"/>',
  'chevron-left': '<path d="m15 5-7 7 7 7"/>',
  'chevron-down': '<path d="m5 9 7 7 7-7"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  heart: '<path d="M12 20.5s-7.5-4.6-9.2-9.4C1.5 7 4 4.5 6.8 4.5c1.9 0 3.4.9 4.2 2.3.8-1.4 2.3-2.3 4.2-2.3 2.8 0 5.3 2.5 4 6.6-1.7 4.8-9.2 9.4-9.2 9.4Z"/>',
  star: '<path d="m12 4 2.3 4.9 5.2.7-3.8 3.7.9 5.2L12 15.9 7.4 18.5l.9-5.2L4.5 9.6l5.2-.7L12 4Z"/>',
  box: '<path d="M21 8.5v7.4a1 1 0 0 1-.5.9l-7 3.9a1 1 0 0 1-1 0l-7-3.9a1 1 0 0 1-.5-.9V8.5"/><path d="m3.5 8.6 7.9 4.4a1 1 0 0 0 1 0l7.6-4.2"/><path d="M12 21.4V12.5"/>',
  'box-open': '<path d="M4 8.5V19a1.5 1.5 0 0 0 1.5 1.5h13A1.5 1.5 0 0 0 20 19V8.5"/><path d="M3 5.5h18v3H3z"/><path d="M12 5.5V4M9.5 11h5"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  location: '<path d="M12 21s6.5-5 6.5-10.5a6.5 6.5 0 1 0-13 0C5.5 16 12 21 12 21Z"/><circle cx="12" cy="10.5" r="2.3"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  'check-circle': '<circle cx="12" cy="12" r="8.5"/><path d="m8 12.5 3 3 5-6"/>',
  pay: '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M3 10h18"/><path d="M7 15h4"/>',
  cancel: '<circle cx="12" cy="12" r="8.5"/><path d="m9 9 6 6M15 9l-6 6"/>',
  receive: '<path d="M21 8.5v7.4a1 1 0 0 1-.5.9l-7 3.9a1 1 0 0 1-1 0l-7-3.9a1 1 0 0 1-.5-.9V8.5"/><path d="m3.5 8.6 7.9 4.4a1 1 0 0 0 1 0l7.6-4.2"/><path d="M12 21.4V12.5"/><path d="m8.5 9.5 3 3 4.5-4.5"/>',
  filter: '<path d="M4 6h16M7 12h10M10 18h4"/>',
  image: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><circle cx="9" cy="10" r="1.8"/><path d="m5 18 4.5-4.5L13 17l3-3 4 4"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  phone: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2Z"/>',
  ticket: '<path d="M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2.5a2 2 0 0 0 0 3V16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2.5a2 2 0 0 0 0-3V8Z"/><path d="M13 6v12"/>',
  edit: '<path d="M4 20h4l10.5-10.5a2 2 0 0 0-2.8-2.8L5.2 17 4 20Z"/><path d="m13.5 6.5 2 2"/>',
  trash: '<path d="M4 7h16"/><path d="M9 7V5.5h6V7"/><path d="M6.5 7l1 13h9l1-13"/><path d="M10 11v6M14 11v6"/>',
  tag: '<path d="M3.5 12V4.5H11l9 9-6.5 6.5-10-10Z"/><path d="M8.5 8.5h.01"/>',
  shield: '<path d="M12 3 4.5 6v5.5c0 4.5 3.2 7.4 7.5 9.5 4.3-2.1 7.5-5 7.5-9.5V6L12 3Z"/><path d="m9 12 2 2 4-4.5"/>',
  document: '<path d="M7 3.5h7l4 4V20.5H7z"/><path d="M14 3.5V7.5h4"/><path d="M10 12h6M10 15.5h6M10 8.5h2"/>',
  swap: '<path d="M4 8h13"/><path d="m14 4.5 3.5 3.5L14 11.5"/><path d="M20 16H7"/><path d="m10 12.5-3.5 3.5L10 19.5"/>',
  'heart-filled': '<path fill="currentColor" stroke="none" d="M12 20.5s-7.5-4.6-9.2-9.4C1.5 7 4 4.5 6.8 4.5c1.9 0 3.4.9 4.2 2.3.8-1.4 2.3-2.3 4.2-2.3 2.8 0 5.3 2.5 4 6.6-1.7 4.8-9.2 9.4-9.2 9.4Z"/>'
}

const inner = computed(() => icons[props.name] || icons.box)
</script>

<template>
  <svg
    class="ds-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    v-html="inner"
  />
</template>

<style scoped>
.ds-icon {
  display: inline-block;
  flex-shrink: 0;
  vertical-align: middle;
}
</style>
