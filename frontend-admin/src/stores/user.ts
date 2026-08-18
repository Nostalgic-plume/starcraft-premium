import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useAuth } from '@shared/js/composables/useAuth'

export const useAdminStore = defineStore('admin', () => {
  const { token, userInfo, isLoggedIn, setToken, clearToken } = useAuth({ tokenKey: 'admin-token', ref })

  /** 登录成功后写入管理员基础信息（nickname 等），供顶栏展示真实昵称 */
  const setUserInfo = (info: Record<string, unknown>) => { userInfo.value = info }

  const logout = () => { clearToken() }

  return { token, userInfo, isLoggedIn, setToken, setUserInfo, clearToken, logout }
})