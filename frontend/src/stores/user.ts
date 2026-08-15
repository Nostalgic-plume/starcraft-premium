import { defineStore } from 'pinia'
import type { Ref } from 'vue'
import { ref } from 'vue'
import { useAuth } from '@shared/js/composables/useAuth'
import { userApi } from '@/api/user'
import type { UserVO } from '@/api/types'

export const useUserStore = defineStore('user', () => {
  const auth = useAuth({ tokenKey: 'token', ref })
  // useAuth 返回 Record<string, unknown>，此处收窄为业务类型 UserVO（同一 ref 对象）
  const userInfo = auth.userInfo as unknown as Ref<UserVO | null>

  const fetchUserInfo = async () => {
    if (!auth.token.value) return
    try {
      const res = await userApi.getInfo()
      userInfo.value = res.data
    } catch (e) {
      console.error('获取用户信息失败:', e)
      auth.clearToken()
    }
  }

  const logout = () => {
    userApi.logout().catch(() => {})
    auth.clearToken()
  }

  return {
    token: auth.token,
    userInfo,
    isLoggedIn: auth.isLoggedIn,
    setToken: auth.setToken,
    clearToken: auth.clearToken,
    fetchUserInfo,
    logout
  }
})
