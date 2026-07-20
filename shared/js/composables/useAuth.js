/**
 * Auth 组合式函数
 * 统一三端 token 管理（ref + localStorage 双向同步）
 *
 * @param {object} opts
 * @param {string} opts.tokenKey - localStorage 中存储 token 的键名
 * @param {Function} opts.ref - Vue ref 函数（由调用方传入，突破 node_modules 解析限制）
 * @returns {{ token: import('vue').Ref<string>, userInfo: import('vue').Ref<object|null>, isLoggedIn: () => boolean, setToken: (t: string) => void, clearToken: () => void }}
 */
export function useAuth({ tokenKey, ref }) {
  const token = ref(localStorage.getItem(tokenKey) || '')
  const userInfo = ref(null)

  const isLoggedIn = () => !!token.value

  const setToken = (t) => {
    token.value = t
    localStorage.setItem(tokenKey, t)
  }

  const clearToken = () => {
    token.value = ''
    localStorage.removeItem(tokenKey)
    userInfo.value = null
  }

  return { token, userInfo, isLoggedIn, setToken, clearToken }
}
