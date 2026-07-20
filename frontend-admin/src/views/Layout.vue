<template>
  <div class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">📊 管理后台</div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#1a1a2e"
        text-color="#fff"
        active-text-color="#e94560"
      >
        <el-menu-item index="/dashboard"><el-icon><DataAnalysis /></el-icon><span>控制台</span></el-menu-item>
        <el-menu-item index="/dashboard/products"><el-icon><Goods /></el-icon><span>商品管理</span></el-menu-item>
        <el-menu-item index="/dashboard/orders"><el-icon><List /></el-icon><span>订单管理</span></el-menu-item>
        <el-menu-item index="/dashboard/users"><el-icon><User /></el-icon><span>用户管理</span></el-menu-item>
        <el-menu-item index="/dashboard/report"><el-icon><Tickets /></el-icon><span>数据看板</span></el-menu-item>
      </el-menu>
    </el-aside>
    <div class="right-area">
      <el-header class="header">
        <div></div>
        <div class="flex-center gap-8">
          <span>{{ adminStore.userInfo?.nickname || adminStore.userInfo?.username }}</span>
          <el-button text @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAdminStore } from '@/stores/user'
import { DataAnalysis, Goods, List, User, Tickets } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const adminStore = useAdminStore()

const activeMenu = computed(() => route.path)

function handleLogout() {
  adminStore.logout()
  router.push('/login')
}
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
  background: #1a1a2e;
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
.logo { height: 60px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 18px; font-weight: bold; border-bottom: 1px solid rgba(255,255,255,0.1); flex-shrink: 0; }
.header { background: #fff; display: flex; align-items: center; justify-content: flex-end; padding: 0 20px; box-shadow: 0 1px 4px rgba(0,0,0,0.04); flex-shrink: 0; height: 60px; }
.main { background: var(--ds-bg); flex: 1; overflow-y: auto; }
.flex-center { display: flex; align-items: center; }
.gap-8 { gap: 8px; }
</style>
