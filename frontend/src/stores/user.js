import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useAuth } from '@shared/js/composables/useAuth'
import { userApi } from '@/api/user'

export const useUserStore = defineStore('user', () => {
  const { token, userInfo, isLoggedIn, setToken, clearToken } = useAuth({ tokenKey: 'token', ref })

  const fetchUserInfo = async () => {
    if (!token.value) return
    try {
      const res = await userApi.getInfo()
      userInfo.value = res.data
    } catch (e) {
      console.error('获取用户信息失败:', e)
      clearToken()
    }
  }

  const logout = () => {
    userApi.logout().catch(() => {})
    clearToken()
  }

  return { token, userInfo, isLoggedIn, setToken, clearToken, fetchUserInfo, logout }
})
