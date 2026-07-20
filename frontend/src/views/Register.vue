<template>
  <div class="auth-page">
    <div class="auth-bg"></div>
    <el-card class="auth-card" shadow="never">
      <div class="auth-header">
        <div class="auth-logo">🛍️</div>
        <h2 class="auth-title">创建账号</h2>
        <p class="auth-subtitle">加入 Shop 商城，开启购物之旅</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="0">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" size="large" class="auth-input" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码（至少6位）" :prefix-icon="Lock" size="large" show-password class="auth-input" />
        </el-form-item>
        <el-form-item prop="phone">
          <el-input v-model="form.phone" placeholder="手机号（选填）" :prefix-icon="Iphone" size="large" class="auth-input" />
        </el-form-item>
        <el-form-item prop="nickname">
          <el-input v-model="form.nickname" placeholder="昵称（选填）" :prefix-icon="UserFilled" size="large" class="auth-input" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" class="auth-btn" :loading="loading" @click="handleRegister">注册</el-button>
        </el-form-item>
      </el-form>
      <div class="auth-footer">
        已有账号？<router-link to="/login">立即登录</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Iphone, UserFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api/user'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: '', password: '', phone: '', nickname: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 6, message: '密码至少6位', trigger: 'blur' }],
  phone: [{
    validator: (rule, value, callback) => {
      if (!value) return callback()
      if (!/^1[3-9]\d{9}$/.test(value)) return callback(new Error('手机号格式不正确'))
      callback()
    },
    trigger: 'blur'
  }]
}

async function handleRegister() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userApi.register(form)
    ElMessage.success('注册成功 🎉')
    router.push('/login')
  } finally { loading.value = false }
}
</script>

<style scoped>
.auth-page {
  display: flex; justify-content: center; align-items: center;
  min-height: calc(100vh - 64px); position: relative; overflow: hidden;
}
.auth-bg {
  position: absolute; inset: 0;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  opacity: 0.05;
}
.auth-card {
  width: 420px; border-radius: 16px; padding: 40px 32px 32px;
  position: relative; z-index: 1; border: 1px solid #f0f0f0;
}
.auth-header { text-align: center; margin-bottom: 28px; }
.auth-logo { font-size: 48px; margin-bottom: 12px; }
.auth-title { font-size: 24px; font-weight: 700; color: #1a1a2e; margin-bottom: 4px; }
.auth-subtitle { font-size: 14px; color: #999; }
.auth-input { --el-input-height: 48px; }
.auth-input :deep(.el-input__wrapper) { border-radius: 10px; padding: 0 16px; }
.auth-btn { width: 100%; height: 48px; border-radius: 10px; font-size: 16px; font-weight: 600; margin-top: 8px; }
.auth-footer { text-align: center; font-size: 14px; color: #999; margin-top: 20px; }
.auth-footer a { color: var(--primary); font-weight: 500; }
</style>
