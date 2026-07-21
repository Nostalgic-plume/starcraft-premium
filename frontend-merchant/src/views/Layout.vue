<template>
  <div class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <span class="logo-mark">S</span>
        <span class="logo-label">商家中心</span>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        class="nav-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon><span>控制台</span>
        </el-menu-item>
        <el-menu-item index="/dashboard/products">
          <el-icon><Goods /></el-icon><span>商品管理</span>
        </el-menu-item>
        <el-menu-item index="/dashboard/orders">
          <el-icon><List /></el-icon><span>订单管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <div class="right-area">
      <el-header class="header">
        <div></div>
        <div class="flex-center gap-8">
          <el-avatar :size="28" :src="store.userInfo?.avatar" style="background: var(--ds-coral, #e94560); flex-shrink: 0;">
            {{ (store.userInfo?.nickname || store.userInfo?.username || 'M')[0] }}
          </el-avatar>
          <span class="user-name">{{ store.userInfo?.nickname || store.userInfo?.username || '商家' }}</span>
          <el-button text type="danger" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useMerchantStore } from '@/stores/user'
import { userApi } from '@/api/user'
import { DataAnalysis, Goods, List } from '@element-plus/icons-vue'

const router = useRouter()
const store = useMerchantStore()

async function fetchUserInfo() {
  try {
    const res = await userApi.getInfo()
    store.userInfo = res.data || res
  } catch (e) {
    console.error('获取用户信息失败:', e)
  }
}

function handleLogout() {
  store.clearToken()
  router.push('/login')
}

onMounted(() => {
  fetchUserInfo()
})
</script>

<style scoped>
.layout { height: 100vh; overflow: hidden; display: flex; }
.aside {
  position: fixed;
  left: 0;
  top: 0;
  width: 220px;
  height: 100vh;
  z-index: 100;
  background: var(--ds-navy, #1a1a2e);
  overflow-y: auto;
}
.right-area {
  margin-left: 220px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--ds-text-inverse, #fff);
  border-bottom: 1px solid rgba(255,255,255,0.08);
  flex-shrink: 0;
}
.logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: var(--ds-coral, #e94560);
  border-radius: var(--ds-radius-sm, 8px);
  color: var(--ds-text-inverse, #fff);
  font-size: 16px;
  font-weight: 800;
  font-family: 'Outfit', sans-serif;
}
.logo-label {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 1px;
}
.header {
  background: var(--ds-surface, #fff);
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 24px;
  box-shadow: var(--ds-shadow-sm, 0 1px 3px rgba(26,26,46,0.06));
  flex-shrink: 0;
  height: 56px;
}
.user-name {
  font-size: 14px;
  color: var(--ds-text, #1a1a2e);
  font-weight: 500;
}
.main {
  background: var(--ds-bg, #f5f3ef);
  flex: 1;
  overflow-y: auto;
}
.flex-center { display: flex; align-items: center; }
.gap-8 { gap: 8px; }

/* Element Plus Menu Override — CSS 变量注入 */
:deep(.nav-menu) {
  --el-menu-bg-color: var(--ds-navy, #1a1a2e);
  --el-menu-text-color: var(--ds-text-inverse, #ffffff);
  --el-menu-active-color: var(--ds-coral, #e94560);
  --el-menu-hover-bg-color: var(--ds-navy-light, #2d2d50);
  border-right: none;
}

:deep(.el-menu-item) {
  transition: var(--ds-transition-fast, all 0.15s ease);
}

:deep(.el-menu-item.is-active) {
  background: var(--ds-navy-light, #2d2d50) !important;
  border-left: 3px solid var(--ds-coral, #e94560) !important;
  font-weight: 600;
}

:deep(.el-menu-item:hover) {
  background: rgba(255,255,255,0.08) !important;
}

:deep(.el-menu-item.is-active:hover) {
  background: var(--ds-navy-light, #2d2d50) !important;
}
</style>
