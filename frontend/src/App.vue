<template>
  <n-config-provider :theme-overrides="themeOverrides">
    <n-message-provider>
      <div id="app-root">
        <!-- 顶部导航（共享组件） -->
        <AppHeader />

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
import { NConfigProvider, NMessageProvider } from 'naive-ui'
import { themeOverrides } from '@shared/js/naive-theme'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import AppHeader from '@/components/AppHeader.vue'
import FloatingChat from '@/components/FloatingChat.vue'
import gsap from 'gsap'

const userStore = useUserStore()
const cartStore = useCartStore()

onMounted(async () => {
  if (userStore.isLoggedIn()) {
    await userStore.fetchUserInfo()
    cartStore.fetchList()
  }
})

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

<style scoped>
.app-main {
  min-height: calc(100dvh - 64px);
  animation: fadeIn 0.4s ease;
}
</style>
