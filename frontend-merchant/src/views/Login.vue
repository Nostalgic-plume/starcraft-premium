<template>
  <div class="login-page">
    <el-card class="login-card" shadow="never">
      <!-- Brand Logo -->
      <div class="login-brand">
        <span class="brand-mark">S</span>
        <span class="brand-label">商家中心</span>
      </div>
      <p class="login-subtitle">请使用店铺账号登录</p>
      <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="店铺用户名" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-form-item>
          <el-button size="large" class="btn-coral" style="width:100%" :loading="loading" @click="handleLogin">登录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useMerchantStore } from '@/stores/user'
import request from '@/api/request'

const router = useRouter()
const store = useMerchantStore()
const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: 'merchant1', password: '123456' })
const rules = {
  username: [{ required: true, message: '请输入店铺用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await request.post('/merchant/login', form)
    store.setToken(res.data.token)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e) {
    // 错误已在响应拦截器中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, var(--ds-bg, #f5f3ef) 0%, #f0e6e6 100%);
}
.login-card {
  width: 420px;
  border-radius: var(--ds-radius-lg, 16px);
  box-shadow: var(--ds-shadow-md, 0 4px 16px rgba(26,26,46,0.08));
}
.login-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 8px;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  background: var(--ds-navy, #1a1a2e);
  border-radius: var(--ds-radius-sm, 8px);
  color: var(--ds-text-inverse, #fff);
  font-size: 18px;
  font-weight: 800;
}
.brand-label {
  font-size: 22px;
  font-weight: 700;
  color: var(--ds-navy, #1a1a2e);
  letter-spacing: 1px;
}
.login-subtitle {
  text-align: center;
  color: var(--ds-text-secondary, #636e72);
  font-size: 14px;
  margin-bottom: 28px;
}
.btn-coral {
  background: var(--ds-coral-gradient, linear-gradient(135deg, #e94560, #ff6b81)) !important;
  border: none !important;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.5px;
  border-radius: var(--ds-radius, 10px);
  color: var(--ds-text-inverse, #fff);
  transition: var(--ds-transition, all 0.3s cubic-bezier(0.4,0,0.2,1));
}
.btn-coral:hover {
  transform: translateY(-1px);
  box-shadow: var(--ds-shadow-coral, 0 4px 20px rgba(233,69,96,0.2));
}
</style>
