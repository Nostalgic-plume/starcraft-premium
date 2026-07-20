<template>
  <el-container style="height: 100vh">
    <el-aside width="220px" style="background: #001529">
      <div class="logo"> 商家中心</div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#001529"
        text-color="#fff"
        active-text-color="#409eff"
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
    <el-container>
      <el-header class="header">
        <div></div>
        <div class="flex-center gap-8">
          <span>{{ store.userInfo?.nickname || store.userInfo?.username || '商家' }}</span>
          <el-button text @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main style="background: #f0f2f5; padding: 0">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
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
.logo { height: 60px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 18px; font-weight: bold; border-bottom: 1px solid rgba(255,255,255,0.1); }
.header { background: #fff; display: flex; align-items: center; justify-content: flex-end; padding: 0 20px; }
</style>
