<template>
  <n-config-provider :theme-overrides="themeOverrides">
    <n-message-provider>
      <div id="app-root">
        <!-- 顶部导航 -->
        <header class="app-header">
          <div class="header-inner">
            <router-link to="/home" class="logo">
              <span class="logo-icon">🛍️</span>
              <span class="logo-text">Shop 商城</span>
            </router-link>
            <nav class="nav-links">
              <router-link to="/home" class="nav-item">首页</router-link>
              <router-link to="/cart" v-if="userStore.isLoggedIn()" class="nav-item">
                购物车
                <el-badge :value="cartStore.items.length" :hidden="cartStore.items.length === 0" class="badge" />
              </router-link>
              <router-link to="/orders" v-if="userStore.isLoggedIn()" class="nav-item">我的订单</router-link>
              <template v-if="userStore.isLoggedIn()">
                <el-dropdown trigger="click" @command="handleUserCommand">
                  <span class="user-info">
                    <el-avatar :size="30" :icon="UserFilled" class="user-avatar" />
                    <span class="user-name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
                  </span>
                  <template #dropdown>
                    <el-dropdown-item command="logout">
                      <el-icon><SwitchButton /></el-icon> 退出登录
                    </el-dropdown-item>
                  </template>
                </el-dropdown>
              </template>
              <template v-else>
                <router-link to="/login" class="nav-item nav-btn">登录</router-link>
                <router-link to="/register" class="nav-item nav-btn nav-btn-reg">注册</router-link>
              </template>
            </nav>
          </div>
        </header>

        <!-- 主内容 -->
        <main class="app-main">
          <router-view v-slot="{ Component }">
            <transition
              mode="out-in"
              @before-leave="pageBeforeLeave"
              @leave="pageLeave"
              @after-leave="pageAfterLeave"
              @before-enter="pageBeforeEnter"
              @enter="pageEnter"
              @after-enter="pageAfterEnter"
            >
              <component :is="Component" />
            </transition>
          </router-view>
        </main>

        <!-- 浮动智能客服 -->
        <FloatingChat />
      </div>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { UserFilled, SwitchButton } from '@element-plus/icons-vue'
import { NConfigProvider, NMessageProvider } from 'naive-ui'
import { themeOverrides } from '@shared/js/naive-theme'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import FloatingChat from '@/components/FloatingChat.vue'
import gsap from 'gsap'

const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

onMounted(async () => {
  if (userStore.isLoggedIn()) {
    await userStore.fetchUserInfo()
    cartStore.fetchList()
  }
})

function handleUserCommand(cmd: string | number | object) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/home')
  }
}

/* ====== GSAP 页面转场 ====== */
function pageBeforeLeave(_el: Element) {
  // 无特殊初始状态
}

function pageLeave(el: Element, done: () => void) {
  gsap.to(el, {
    opacity: 0,
    scale: 0.97,
    y: -8,
    duration: 0.2,
    ease: 'power2.in',
    onComplete: done
  })
}

function pageAfterLeave(el: Element) {
  gsap.set(el, { clearProps: 'all' })
}

function pageBeforeEnter(el: Element) {
  gsap.set(el, {
    opacity: 0,
    scale: 0.97,
    y: 12
  })
}

function pageEnter(el: Element, done: () => void) {
  gsap.to(el, {
    opacity: 1,
    scale: 1,
    y: 0,
    duration: 0.35,
    ease: 'power2.out',
    onComplete: done
  })
}

function pageAfterEnter(el: Element) {
  gsap.set(el, { clearProps: 'all' })
}
</script>

<style>
/* GSAP 转场由 JavaScript 驱动，见 script 中 pageLeave/pageEnter */
</style>

<style scoped>
.app-header {
  background: var(--ds-navy, #1a1a2e);
  position: sticky;
  top: 0;
  z-index: 100;
  box-shadow: var(--ds-shadow-md, 0 4px 16px rgba(26,26,46,0.08));
  height: 64px;
}
.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 0 24px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  text-decoration: none;
}
.logo-icon { font-size: 28px; }
.logo-text {
  font-size: 22px;
  color: #fff;
  letter-spacing: -0.5px;
  font-weight: 800;
}

.nav-links { display: flex; align-items: center; gap: 4px; }
.nav-item {
  padding: 8px 16px;
  color: rgba(255,255,255,0.75);
  font-size: 14px;
  font-weight: 500;
  border-radius: var(--ds-radius-sm, 6px);
  transition: var(--ds-transition);
  position: relative;
  text-decoration: none;
}
.nav-item:hover {
  color: #fff;
  background: rgba(255,255,255,0.08);
}
/* 珊瑚红下划线 hover 动效 */
.nav-item::after {
  content: '';
  position: absolute;
  bottom: 2px;
  left: 50%;
  transform: translateX(-50%) scaleX(0);
  width: 20px;
  height: 2.5px;
  background: var(--ds-coral, #e94560);
  border-radius: 2px;
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.nav-item:hover::after {
  transform: translateX(-50%) scaleX(1);
}
/* 活跃导航指示器 */
.nav-item.router-link-exact-active {
  color: var(--ds-coral, #e94560) !important;
  font-weight: 600;
}
.nav-item.router-link-exact-active::after {
  transform: translateX(-50%) scaleX(1);
}
.nav-btn {
  padding: 8px 20px;
  border-radius: var(--ds-radius, 10px);
  font-weight: 600;
  border: 1.5px solid rgba(255,255,255,0.6);
  color: #fff !important;
  background: transparent;
}
.nav-btn:hover { background: rgba(255,255,255,0.12) !important; border-color: #fff; transform: translateY(-1px); }
/* 注册按钮：珊瑚红填充 */
.nav-btn-reg {
  background: var(--ds-coral, #e94560) !important;
  border-color: var(--ds-coral, #e94560);
  color: #fff !important;
}
.nav-btn-reg:hover { background: var(--ds-coral-light, #ff6b81) !important; border-color: var(--ds-coral-light, #ff6b81) !important; }

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 12px 4px 4px;
  border-radius: var(--ds-radius, 10px);
  transition: var(--ds-transition);
}
.user-info:hover { background: rgba(255,255,255,0.08); }
.user-avatar { border: 2px solid rgba(255,255,255,0.2); }
.user-name {
  font-size: 14px;
  color: rgba(255,255,255,0.85);
  font-weight: 500;
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge :deep(.el-badge__content) {
  background: var(--ds-coral, #e94560);
  border: none;
  color: #fff;
  font-size: 11px;
  height: 18px;
  line-height: 18px;
}

.app-main {
  min-height: calc(100dvh - 64px);
  animation: fadeIn 0.4s ease;
}
</style>
