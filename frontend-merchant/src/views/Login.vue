<template>
  <div class="login-page">
    <div class="login-card">
      <!-- Brand Logo -->
      <div class="login-brand">
        <div class="brand-mark">星</div>
        <div class="brand-label">商家中心</div>
      </div>
      <p class="login-subtitle">请使用店铺账号登录</p>

      <n-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :show-label="false"
        size="large"
        @keyup.enter="handleLogin"
      >
        <n-form-item path="username">
          <n-input v-model:value="form.username" placeholder="店铺用户名" clearable />
        </n-form-item>
        <n-form-item path="password">
          <n-input
            v-model:value="form.password"
            type="password"
            placeholder="密码"
            show-password-on="click"
          />
        </n-form-item>
        <n-button class="login-btn" :loading="loading" @click="handleLogin">登录</n-button>
      </n-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useMerchantStore } from '@/stores/user'
import request from '@/api/request'

const router = useRouter()
const message = useMessage()
const store = useMerchantStore()

const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const form = reactive({ username: 'merchant1', password: '123456' })

const rules: FormRules = {
  username: {
    required: true,
    message: '请输入店铺用户名',
    trigger: ['blur', 'input']
  },
  password: {
    required: true,
    message: '请输入密码',
    trigger: ['blur', 'input']
  }
}

async function handleLogin() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const res = await request.post('/merchant/login', form)
    store.setToken(res.data.token)
    message.success('登录成功')
    router.push('/dashboard')
  } catch {
    /* 登录失败提示由响应拦截器统一处理 */
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
  background:
    radial-gradient(ellipse at 20% 40%, var(--ds-primary-tint, rgba(45, 127, 249, 0.08)) 0%, transparent 60%),
    radial-gradient(ellipse at 80% 60%, rgba(45, 127, 249, 0.05) 0%, transparent 60%),
    var(--ds-bg, #f6f7fa);
}
.login-card {
  width: 420px;
  max-width: 100%;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 40px 32px 32px;
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.login-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  border-radius: 18px;
  color: #fff;
  font-size: 24px;
  font-weight: 800;
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
  font-size: 14px;
  margin-bottom: 24px;
}
.login-btn {
  width: 100%;
  height: 44px;
  margin-top: 4px;
  border-radius: var(--ds-r, 12px);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.5px;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9)) !important;
  border: none !important;
  color: #fff !important;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25)) !important;
}
.login-btn:hover {
  box-shadow: 0 6px 20px rgba(45, 127, 249, 0.32) !important;
  transform: translateY(-1px);
}
</style>
