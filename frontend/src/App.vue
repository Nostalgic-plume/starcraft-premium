<template>
  <n-config-provider :theme-overrides="themeOverrides">
    <n-dialog-provider>
      <n-message-provider>
        <div id="app-root">
        <!-- 顶部导航（共享组件） -->
        <AppHeader />

        <!-- 主内容 -->
        <main class="app-main">
          <router-view v-slot="{ Component }">
            <transition
              name="page"
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
    </n-dialog-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { NConfigProvider, NDialogProvider, NMessageProvider } from 'naive-ui'
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

/* ====== GSAP 页面转场 ======
 * 并行转场（不使用 mode="out-in"）：新旧页面同时交叉淡化，切换时长由
 * 0.55s（0.2s 串行淡出「屏幕先变空」+ 0.35s 淡入）降到 0.2s，新页面点击即开始渲染。
 * ⚠️ 并行转场下新旧页面会同时存在于文档流，离场页面必须脱离文档流，
 *    否则两页高度叠加会导致滚动位置跳动 —— 见 .page-leave-active。
 */
function pageBeforeLeave(_el: Element) {
  // 无特殊初始状态
}

function pageLeave(el: Element, done: () => void) {
  // 立即让离场页面脱离文档流：并行转场下新旧页面同时在 DOM 里，
  // 不脱离会按两页高度叠加，导致滚动位置跳动（用内联样式而非 CSS 类，
  // 避免依赖 scoped 样式能否作用到子组件根元素）
  gsap.set(el, { position: 'absolute', top: 0, left: 0, width: '100%' })
  gsap.to(el, {
    opacity: 0,
    scale: 0.99,
    duration: 0.18,
    ease: 'power2.in',
    onComplete: done
  })
}

function pageAfterLeave(el: Element) {
  // 只清视觉属性，保留 pageLeave 设的 position: absolute —— 元素此刻即将被移除，
  // 若把 position 一并清掉会闪回文档流撑开高度，滚动条抖动一下
  gsap.set(el, { clearProps: 'opacity,transform' })
}

function pageBeforeEnter(el: Element) {
  gsap.set(el, {
    opacity: 0,
    scale: 0.99,
    y: 8
  })
}

function pageEnter(el: Element, done: () => void) {
  gsap.to(el, {
    opacity: 1,
    scale: 1,
    y: 0,
    duration: 0.2,
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
  /* 并行转场期间离场页面（pageLeave 里设为 absolute）相对本容器定位 */
  position: relative;
}
</style>
