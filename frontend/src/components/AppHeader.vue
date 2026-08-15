<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NDropdown } from 'naive-ui'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import SvgIcon from './SvgIcon.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

const keyword = ref('')

const navItems = [
  { label: '首页', path: '/home', icon: 'home', auth: false },
  { label: '购物车', path: '/cart', icon: 'cart', auth: true },
  { label: '我的订单', path: '/orders', icon: 'orders', auth: true }
] as const

/** 登录态控制导航显隐 */
const visibleNavItems = computed(() =>
  navItems.filter(item => !item.auth || userStore.isLoggedIn())
)

const userMenuOptions = [
  { label: '退出登录', key: 'logout' }
]

function onSearch() {
  const q = keyword.value.trim()
  router.push({ path: '/home', query: q ? { keyword: q } : {} })
}

function onUserMenuSelect(key: string) {
  if (key === 'logout') {
    userStore.logout()
    router.push('/home')
  }
}

/** 是否高亮当前导航项 */
function isActive(path: string): boolean {
  if (path === '/home') return route.path === '/home'
  return route.path.startsWith(path)
}
</script>

<template>
  <header class="app-header">
    <div class="header-inner">
      <!-- Logo -->
      <router-link to="/home" class="logo">
        <span class="logo-mark">星</span>
        <span class="logo-text">星创优品</span>
      </router-link>

      <!-- 主导航 -->
      <nav class="nav-links">
        <router-link
          v-for="item in visibleNavItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
        >
          <SvgIcon :name="item.icon" :size="16" />
          <span>{{ item.label }}</span>
          <span v-if="item.path === '/cart' && cartStore.items.length" class="cart-badge">
            {{ cartStore.items.length > 99 ? '99+' : cartStore.items.length }}
          </span>
        </router-link>
      </nav>

      <!-- 右侧：搜索 + 登录态 -->
      <div class="header-right">
        <div class="search-box">
          <SvgIcon name="search" :size="16" class="search-icon" />
          <input
            v-model="keyword"
            class="search-input"
            placeholder="搜索商品"
            @keyup.enter="onSearch"
          />
        </div>

        <template v-if="userStore.isLoggedIn()">
          <n-dropdown :options="userMenuOptions" trigger="click" @select="onUserMenuSelect">
            <div class="user-chip">
              <span class="user-avatar">
                <SvgIcon name="user" :size="16" />
              </span>
              <span class="user-name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username || '用户' }}</span>
            </div>
          </n-dropdown>
        </template>
        <template v-else>
          <router-link to="/login" class="nav-btn">登录</router-link>
          <router-link to="/register" class="nav-btn nav-btn-primary">注册</router-link>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: var(--ds-card, #ffffff);
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
  box-shadow: 0 1px 8px rgba(31, 38, 48, 0.04);
  height: 64px;
}
.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 24px;
  height: 100%;
  padding: 0 24px;
}

/* Logo */
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  flex-shrink: 0;
}
.logo-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  color: #fff;
  font-size: 20px;
  font-weight: 800;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
}
.logo-text {
  font-size: 20px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
}

/* 导航 */
.nav-links {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
}
.nav-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: var(--ds-r, 12px);
  color: var(--ds-text-2, #5a6474);
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  transition: all 0.2s ease;
  position: relative;
}
.nav-item:hover {
  color: var(--ds-primary, #2d7ff9);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.nav-item.active {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}
.nav-item.active::after {
  content: '';
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 2px;
  height: 2px;
  border-radius: 1px;
  background: var(--ds-primary, #2d7ff9);
}
.cart-badge {
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 9px;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 17px;
  text-align: center;
}

/* 右侧 */
.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}
.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--ds-line-2, #d8dce4);
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-bg-2, #eef0f4);
  transition: all 0.2s ease;
}
.search-box:focus-within {
  border-color: var(--ds-primary, #2d7ff9);
  background: #fff;
  box-shadow: 0 0 0 3px var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.search-icon {
  color: var(--ds-text-3, #98a1af);
  flex-shrink: 0;
}
.search-input {
  width: 150px;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--ds-text, #1e2632);
}
.search-input::placeholder {
  color: var(--ds-text-3, #98a1af);
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  border-radius: var(--ds-r-pill, 999px);
  cursor: pointer;
  transition: background 0.2s ease;
}
.user-chip:hover {
  background: var(--ds-bg-2, #eef0f4);
}
.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-btn {
  padding: 8px 18px;
  border-radius: var(--ds-r, 12px);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  color: var(--ds-text-2, #5a6474);
  border: 1px solid var(--ds-line-2, #d8dce4);
  transition: all 0.2s ease;
}
.nav-btn:hover {
  color: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
}
.nav-btn-primary {
  background: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
  color: #fff;
}
.nav-btn-primary:hover {
  background: var(--ds-primary-strong, #1e63d6);
  border-color: var(--ds-primary-strong, #1e63d6);
  color: #fff;
}

@media (max-width: 900px) {
  .search-input { width: 90px; }
  .logo-text { display: none; }
}
</style>
