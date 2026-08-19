import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useAuth } from '@shared/js/composables/useAuth'

export const useMerchantStore = defineStore('merchant', () => {
  const { token, userInfo, isLoggedIn, setToken, clearToken } = useAuth({ tokenKey: 'merchant-token', ref })

  return { token, userInfo, isLoggedIn, setToken, clearToken }
})
