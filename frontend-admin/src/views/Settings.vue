<template>
  <div class="placeholder-page fade-in">
    <n-card class="placeholder-card" :bordered="true">
      <div class="ph-body">
        <span class="ph-icon" v-html="iconSvg"></span>
        <div class="ph-title">系统设置</div>
        <div class="ph-desc">模块建设中，将在后续批次提供管理员账号、角色权限等系统配置</div>
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { NCard } from 'naive-ui'

// 内联 SVG（sliders 线性图标，中性蓝）
const iconSvg = `<svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M1 14h6M9 8h6M17 16h6"/></svg>`
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