<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">管理后台登录</h2>
      <p class="text-muted">请使用管理员账号登录</p>
      <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="handleLogin">登录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAdminStore } from '@/stores/user'
import request from '@/api/request'

const router = useRouter()
const store = useAdminStore()
const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: 'admin', password: '123456' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    // 直接调用管理员独立登录接口，admin_id 作为 Sa-Token loginId
    const res = await request.post('/admin/login', form)
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
.login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: var(--ds-bg); }
.login-card { width: 420px; border-radius: var(--ds-radius-lg, 16px); }
.login-title { text-align: center; margin-bottom: 8px; font-size: 22px; color: var(--ds-navy, #1a1a2e); }
.text-muted { text-align: center; color: #999; font-size: 13px; margin-bottom: 24px; }
</style>
