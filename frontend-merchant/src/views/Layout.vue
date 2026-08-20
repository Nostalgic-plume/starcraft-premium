<template>
  <n-layout has-sider class="layout-root">
    <!-- 左侧 180px 导航 -->
    <n-layout-sider
      bordered
      class="sider"
      :width="180"
      :collapsed-width="0"
      :show-trigger="false"
      :native-scrollbar="false"
    >
      <div class="logo">
        <span class="logo-mark">S</span>
        <span class="logo-text">商家中心</span>
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
        <div class="shop-brand">
          <n-avatar round :size="32" :src="shopLogo || undefined" class="shop-avatar">
            {{ avatarText }}
          </n-avatar>
          <span class="shop-name">{{ shopName }}</span>
        </div>
        <div class="header-right">
          <span class="user-name">{{ username }}</span>
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
import { computed, h, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAvatar, NButton, NLayout, NLayoutContent, NLayoutHeader, NLayoutSider, NMenu } from 'naive-ui'
import type { MenuOption } from 'naive-ui'
import { merchantApi } from '@/api/merchant'
import type { ShopInfoVO } from '@/api/types'
import { useMerchantStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const store = useMerchantStore()

const activeMenu = computed(() => route.path)

/** 当前店铺信息（读 /merchant/info → shopName/shopLogo） */
const shopInfo = ref<Partial<ShopInfoVO> | null>(null)
const shopName = computed(() => shopInfo.value?.shopName || '商家中心')
const shopLogo = computed(() => shopInfo.value?.shopLogo || '')
const username = computed(() => shopInfo.value?.username || shopInfo.value?.contactName || '商家')
const avatarText = computed(() => (shopName.value || '商').slice(0, 1))

/** 菜单 key 与路由路径一一对应（全部为真实路由，含本批 4 个功能页） */

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
  { label: '本店看板', key: '/dashboard', icon: renderIcon('M18 20V10M12 20V4M6 20v-6') },
  { label: '商品管理', key: '/dashboard/products', icon: renderIcon('M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16zM3.27 6.96L12 12.01l8.73-5.05M12 22.08V12') },
  { label: '订单管理', key: '/dashboard/orders', icon: renderIcon('M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8') },
  { label: '优惠券', key: '/dashboard/coupons', icon: renderIcon('M20 12a4 4 0 0 0-4-4h-1V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2v-2h1a4 4 0 0 0 4-4zM14 6h.01') },
  { label: '评价管理', key: '/dashboard/reviews', icon: renderIcon('M12 2l2.9 6.26L21 9.27l-4.5 4.38L17.8 20 12 16.77 6.2 20l1.3-6.35L3 9.27l6.1-1.01L12 2z') },
  { label: '售后处理', key: '/dashboard/after-sales', icon: renderIcon('M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8M3 3v5h5') },
  { label: '库存预警', key: '/dashboard/stock-alert', icon: renderIcon('M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0') }
]

function handleMenuSelect(key: string) {
  if (key !== route.path) router.push(key)
}

async function fetchShopInfo() {
  try {
    const res = await merchantApi.getInfo()
    const data = (res?.data || {}) as ShopInfoVO
    shopInfo.value = data
    store.userInfo = data as unknown as Record<string, unknown>
  } catch (e) {
    console.error('获取商家信息失败:', e)
  }
}

function handleLogout() {
  store.clearToken()
  router.push('/login')
}

onMounted(() => {
  fetchShopInfo()
})
</script>

<style scoped>
.layout-root {
  height: 100vh;
}

/* ====== 左侧 180px 浅色导航（中性底 + 蓝 tint） ====== */
.sider {
  background: var(--ds-card, #fff);
  border-right: 1px solid var(--ds-line, #e7e9ee);
}

.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--ds-text, #1e2632);
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
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

/* NMenu 浅色覆写（选中蓝 tint） */
.nav-menu {
  --n-item-color-active: rgba(45, 127, 249, 0.1);
  --n-item-color-active-hover: rgba(45, 127, 249, 0.16);
  --n-item-color-hover: rgba(31, 38, 48, 0.05);
  --n-item-text-color: var(--ds-text-2, #5a6474);
  --n-item-text-color-hover: var(--ds-text, #1e2632);
  --n-item-text-color-active: var(--ds-primary, #2d7ff9);
  --n-item-text-color-active-hover: var(--ds-primary, #2d7ff9);
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
.shop-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.shop-avatar {
  background: linear-gradient(135deg, #5aa3ff, #2d7ff9);
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}
.shop-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.user-name {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
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
