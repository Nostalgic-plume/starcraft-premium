<template>
  <n-layout has-sider class="layout-root">
    <!-- 左侧 180px 导航 -->
    <n-layout-sider bordered class="sider" :width="180" :collapsed-width="0" :show-trigger="false" :native-scrollbar="false">
      <div class="logo">
        <span class="logo-mark">S</span>
        <span class="logo-text">星创管理后台</span>
      </div>
      <n-menu
        class="nav-menu"
        :value="activeMenu"
        :options="menuOptions"
        :root-indent="16"
        @update:value="handleMenuSelect"
      />
    </n-layout-sider>

    <!-- 右侧：顶栏 + 内容区 -->
    <n-layout class="right-area">
      <n-layout-header bordered class="header">
        <div class="page-title">{{ pageTitle }}</div>
        <div class="header-right">
          <n-avatar round :size="30" class="avatar">{{ avatarText }}</n-avatar>
          <span class="user-name">{{ userName }}</span>
          <n-button quaternary size="small" class="logout-btn" @click="handleLogout">退出</n-button>
        </div>
      </n-layout-header>
      <n-layout-content class="content" :native-scrollbar="false">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<script setup lang="ts">
import { computed, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAvatar, NButton, NLayout, NLayoutContent, NLayoutHeader, NLayoutSider, NMenu } from 'naive-ui'
import type { MenuOption } from 'naive-ui'
import { useAdminStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const adminStore = useAdminStore()

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => (route.meta.title as string | undefined) || '管理后台')

/** SVG 线性图标渲染（中性蓝，随文字 currentColor 着色） */
function renderIcon(d: string) {
  return () =>
    h(
      'svg',
      {
        viewBox: '0 0 24 24',
        width: '16',
        height: '16',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': '2',
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round'
      },
      [h('path', { d })]
    )
}

const menuOptions: MenuOption[] = [
  { label: '数据看板', key: '/dashboard', icon: renderIcon('M18 20V10M12 20V4M6 20v-6') },
  { label: '商品管理', key: '/dashboard/products', icon: renderIcon('M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16zM3.27 6.96L12 12.01l8.73-5.05M12 22.08V12') },
  { label: '订单管理', key: '/dashboard/orders', icon: renderIcon('M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8') },
  { label: '用户管理', key: '/dashboard/users', icon: renderIcon('M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75') },
  { label: '分类管理', key: '/dashboard/categories', icon: renderIcon('M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82zM7 7h.01') },
  { label: '系统设置', key: '/dashboard/settings', icon: renderIcon('M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6') }
]

function handleMenuSelect(key: string) {
  if (key !== route.path) router.push(key)
}

const userName = computed(() => {
  const u = adminStore.userInfo as Record<string, unknown> | null
  return String((u?.nickname as string) || (u?.username as string) || '管理员')
})
const avatarText = computed(() => userName.value.slice(0, 1).toUpperCase())

function handleLogout() {
  adminStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout-root {
  height: 100vh;
}

/* ====== 左侧深色导航 ====== */
.sider {
  background: #17222f;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
}

.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}
.logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #5aa3ff, #2d7ff9);
  color: #fff;
  font-size: 15px;
  font-weight: 800;
  font-family: var(--ds-mono, Consolas, monospace);
}
.logo-text {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

/* NMenu 深色覆写（CSS 变量注入，替代 theme="dark" 默认配色） */
.nav-menu {
  --n-item-color-active: rgba(45, 127, 249, 0.22);
  --n-item-color-active-hover: rgba(45, 127, 249, 0.3);
  --n-item-color-hover: rgba(255, 255, 255, 0.07);
  --n-item-color-active-collapsed: rgba(45, 127, 249, 0.22);
  --n-item-text-color: rgba(255, 255, 255, 0.66);
  --n-item-text-color-hover: rgba(255, 255, 255, 0.92);
  --n-item-text-color-active: #fff;
  --n-item-text-color-active-hover: #fff;
  --n-item-border-radius: 10px;
  padding: 12px 10px;
}
.nav-menu :deep(.n-menu-item-content) {
  border-left: 3px solid transparent;
}
.nav-menu :deep(.n-menu-item-content--active) {
  border-left-color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}

/* ====== 右侧区域 ====== */
.right-area {
  min-width: 0;
}
.header {
  height: 56px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
  flex-shrink: 0;
}
.page-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
}
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.avatar {
  background: linear-gradient(135deg, #5aa3ff, #2d7ff9);
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}
.user-name {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
  font-weight: 500;
}
.logout-btn {
  color: var(--ds-text-3, #98a1af);
}
.logout-btn:hover {
  color: var(--ds-red, #e0404e);
}

.content {
  background: var(--ds-bg, #f6f7fa);
  height: calc(100vh - 56px);
  overflow: auto;
}
</style>