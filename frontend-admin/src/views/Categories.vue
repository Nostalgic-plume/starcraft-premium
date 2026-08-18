<template>
  <div class="placeholder-page fade-in">
    <n-card class="placeholder-card" :bordered="true">
      <div class="ph-body">
        <span class="ph-icon" v-html="iconSvg"></span>
        <div class="ph-title">分类管理</div>
        <div class="ph-desc">模块建设中，将在后续批次提供完整的商品分类维护能力</div>
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { NCard } from 'naive-ui'

// 内联 SVG（folder/tag 线性图标，中性蓝）
const iconSvg = `<svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><path d="M7 7h.01"/></svg>`
</script>

<style scoped>
.placeholder-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 40px 20px;
}
.placeholder-card {
  border-radius: var(--ds-r-lg, 16px);
}
.ph-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 16px;
  text-align: center;
}
.ph-icon {
  color: var(--ds-primary, #2d7ff9);
}
.ph-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
}
.ph-desc {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
</style>