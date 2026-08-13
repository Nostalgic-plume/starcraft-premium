import type { Ref } from 'vue'

export interface UseAuthOptions {
  tokenKey: string
  ref: <T>(value: T) => Ref<T>
}

export function useAuth({ tokenKey, ref }: UseAuthOptions) {
  const token = ref<string>(localStorage.getItem(tokenKey) || '')
  const userInfo = ref<Record<string, unknown> | null>(null)
  const isLoggedIn = () => !!token.value
  const setToken = (t: string) => { token.value = t; localStorage.setItem(tokenKey, t) }
  const clearToken = () => { token.value = ''; localStorage.removeItem(tokenKey); userInfo.value = null }
  return { token, userInfo, isLoggedIn, setToken, clearToken }
}