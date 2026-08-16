<template>
  <div class="auth-page">
    <div class="auth-bg"></div>
    <div class="auth-card">
      <div class="auth-header">
        <div class="auth-logo">
          <SvgIcon name="star" :size="30" />
        </div>
        <h2 class="auth-title">欢迎回来</h2>
        <p class="auth-subtitle">登录星创优品，继续你的购物之旅</p>
      </div>

      <n-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :show-label="false"
        size="large"
        @keyup.enter="handleLogin"
      >
        <n-form-item path="username">
          <n-input v-model:value="form.username" placeholder="用户名" clearable>
            <template #prefix>
              <SvgIcon name="user" :size="16" />
            </template>
          </n-input>
        </n-form-item>
        <n-form-item path="password">
          <n-input
            v-model:value="form.password"
            type="password"
            placeholder="密码"
            show-password-on="click"
          >
            <template #prefix>
              <SvgIcon name="lock" :size="16" />
            </template>
          </n-input>
        </n-form-item>
        <n-button
          class="auth-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登录
        </n-button>
      </n-form>

      <div class="auth-footer">
        还没有账号？<router-link to="/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { userApi } from '@/api/user'
import { useUserStore } from '@/stores/user'
import SvgIcon from '@/components/SvgIcon.vue'

const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: {
    required: true,
    message: '请输入用户名',
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
    const res = await userApi.login(form)
    userStore.setToken(res.data.token)
    await userStore.fetchUserInfo()
    // 按角色跳转：admin → 管理后台，merchant → 商家中心，user → 首页
    const roleRes = await userApi.getRole()
    const roles = roleRes.data || []
    if (roles.includes('admin')) {
      window.location.href = 'http://localhost:3001/'
    } else if (roles.includes('merchant')) {
      window.location.href = 'http://localhost:3002/'
    } else {
      message.success('登录成功')
      router.push('/home')
    }
  } catch {
    /* 登录失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: calc(100vh - 64px);
  position: relative;
  overflow: hidden;
  padding: 32px 16px;
}
.auth-bg {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 20% 40%, var(--ds-primary-tint, rgba(45, 127, 249, 0.08)) 0%, transparent 60%),
    radial-gradient(ellipse at 80% 60%, rgba(45, 127, 249, 0.05) 0%, transparent 60%);
}
.auth-card {
  width: 420px;
  max-width: 100%;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 40px 32px 32px;
  position: relative;
  z-index: 1;
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.auth-header {
  text-align: center;
  margin-bottom: 28px;
}
.auth-logo {
  width: 64px;
  height: 64px;
  margin: 0 auto 14px;
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
}
.auth-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0 0 6px;
}
.auth-subtitle {
  font-size: 14px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}
.auth-btn {
  width: 100%;
  height: 48px;
  margin-top: 4px;
  border-radius: var(--ds-r, 12px);
  font-size: 16px;
  font-weight: 700;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9)) !important;
  border: none !important;
  color: #fff !important;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25)) !important;
}
.auth-btn:hover {
  box-shadow: 0 6px 20px rgba(45, 127, 249, 0.32) !important;
  transform: translateY(-1px);
}
.auth-footer {
  text-align: center;
  font-size: 14px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 20px;
}
.auth-footer a {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
  text-decoration: none;
  transition: color 0.2s;
}
.auth-footer a:hover {
  color: var(--ds-primary-strong, #1e63d6);
  text-decoration: underline;
}
</style>
