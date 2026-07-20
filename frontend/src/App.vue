<template>
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
        <transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <!-- 浮动智能客服 -->
    <FloatingChat />
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { UserFilled, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import FloatingChat from '@/components/FloatingChat.vue'

const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

onMounted(async () => {
  if (userStore.isLoggedIn()) {
    await userStore.fetchUserInfo()
    cartStore.fetchList()
  }
})

function handleUserCommand(cmd) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/home')
  }
}
</script>

<style>
/* ====== 全局转场动画 ====== */
.page-fade-enter-active, .page-fade-leave-active { transition: opacity 0.25s ease, transform 0.25s ease; }
.page-fade-enter-from { opacity: 0; transform: translateY(12px); }
.page-fade-leave-to { opacity: 0; transform: translateY(-8px); }
</style>

<style scoped>
.app-header {
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
  position: sticky;
  top: 0;
  z-index: 100;
  box-shadow: 0 1px 8px rgba(0,0,0,0.04);
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
}
.logo-icon { font-size: 28px; }
.logo-text { font-size: 22px; color: #1a1a2e; letter-spacing: -0.5px; }

.nav-links { display: flex; align-items: center; gap: 6px; }
.nav-item {
  padding: 8px 16px;
  color: #555;
  font-size: 14px;
  border-radius: 8px;
  transition: all 0.2s;
}
.nav-item:hover { color: var(--primary); background: #f0f7ff; }
.nav-btn {
  background: var(--primary);
  color: #fff !important;
  padding: 8px 20px;
  border-radius: 20px;
  font-weight: 500;
}
.nav-btn:hover { background: var(--primary-dark) !important; }
.nav-btn-reg {
  background: #fff;
  color: var(--primary) !important;
  border: 1.5px solid var(--primary);
}
.nav-btn-reg:hover { background: #f0f7ff !important; }

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 12px 4px 4px;
  border-radius: 20px;
  transition: background 0.2s;
}
.user-info:hover { background: #f5f5f5; }
.user-avatar { border: 2px solid #e8e8e8; }
.user-name { font-size: 14px; color: #333; max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge { margin-left: 2px; }

.app-main {
  min-height: calc(100vh - 64px);
  animation: fadeIn 0.4s ease;
}
</style>
