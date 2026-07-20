import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useAuth } from '@shared/js/composables/useAuth.js'

export const useAdminStore = defineStore('admin', () => {
  const { token, userInfo, isLoggedIn, setToken, clearToken } = useAuth({ tokenKey: 'admin-token', ref })

  const logout = () => { clearToken() }

  return { token, userInfo, isLoggedIn, setToken, clearToken, logout }
})
