<template>
  <div class="login-page">
    <n-card class="login-card" :bordered="true">
      <!-- 蓝渐变「星」Logo -->
      <div class="login-brand">
        <span class="brand-mark">星</span>
        <span class="brand-label">星创管理后台</span>
      </div>
      <p class="login-subtitle">请使用管理员账号登录（admin 表独立账号）</p>

      <n-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        @keyup.enter="handleLogin"
      >
        <n-form-item path="username">
          <n-input
            v-model:value="form.username"
            placeholder="管理员用户名"
            clearable
          />
        </n-form-item>
        <n-form-item path="password">
          <n-input
            v-model:value="form.password"
            type="password"
            placeholder="密码"
            show-password-on="click"
          />
        </n-form-item>
        <n-button
          type="primary"
          size="large"
          block
          :loading="loading"
          class="login-btn"
          @click="handleLogin"
        >
          登录
        </n-button>
      </n-form>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NForm, NFormItem, NInput, useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useAdminStore } from '@/stores/user'
import request from '@/api/request'

const router = useRouter()
const store = useAdminStore()
const message = useMessage()

const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const form = reactive({ username: 'admin', password: '123456' })

const rules: FormRules = {
  username: { required: true, message: '请输入用户名', trigger: ['input', 'blur'] },
  password: { required: true, message: '请输入密码', trigger: ['input', 'blur'] }
}

async function handleLogin() {
  try {
    await formRef.value?.validate()
  } catch {
    // 表单校验未通过
    return
  }
  loading.value = true
  try {
    // 管理员独立登录接口（/api/admin/login，admin 表独立账号，loginId = admin_id）
    const res = await request.post('/admin/login', form)
    store.setToken(res.data.token)
    message.success('登录成功')
    router.push('/dashboard')
  } catch {
    // 错误提示已在响应拦截器统一处理
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
  background: linear-gradient(135deg, #f3f7fe 0%, #e7effb 100%);
}
.login-card {
  width: 420px;
  border-radius: var(--ds-r-lg, 16px);
  box-shadow: var(--ds-shadow-brand, 0 8px 30px rgba(45, 127, 249, 0.14));
  border-color: var(--ds-line, #e7e9ee);
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
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: linear-gradient(135deg, #5aa3ff 0%, #2d7ff9 55%, #1e63d6 100%);
  color: #fff;
  font-size: 20px;
  font-weight: 800;
  font-family: var(--ds-sans, "PingFang SC", sans-serif);
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
}
.brand-label {
  font-size: 22px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  letter-spacing: 1px;
}
.login-subtitle {
  text-align: center;
  color: var(--ds-text-3, #98a1af);
  font-size: 13px;
  margin-bottom: 24px;
}
.login-btn {
  margin-top: 4px;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 1px;
  border-radius: var(--ds-r, 12px);
}
</style>
