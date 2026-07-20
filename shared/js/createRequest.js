/**
 * Axios Request 工厂
 * 统一三端请求配置、拦截器、错误处理
 *
 * @param {object} opts
 * @param {string} opts.tokenKey - localStorage 中存储 token 的键名
 * @param {import('vue-router').Router|null} opts.router - Vue Router 实例（401 时跳转登录页）
 * @param {import('axios')} opts.axios - Axios 库（由调用方传入，确保正确解析 node_modules）
 * @param {object} opts.ElMessage - Element Plus ElMessage 对象
 * @returns {import('axios').AxiosInstance}
 */
export function createRequest({ tokenKey, router, axios, ElMessage }) {
  const request = axios.create({
    baseURL: '/api',
    timeout: 15000
  })

  // 请求拦截器：自动携带 token
  request.interceptors.request.use(config => {
    const token = localStorage.getItem(tokenKey)
    if (token) {
      config.headers['token'] = token
    }
    return config
  })

  // 响应拦截器：统一处理业务错误和网络错误
  request.interceptors.response.use(
    // 业务响应：后端统一返回 { code, msg, data }
    res => {
      const data = res.data
      if (data.code && data.code !== 200) {
        ElMessage.error(data.msg || '请求失败')
        return Promise.reject(new Error(data.msg))
      }
      return data
    },
    // HTTP 错误（网络异常、401 等）
    error => {
      if (error.response?.status === 401) {
        localStorage.removeItem(tokenKey)
        if (router) {
          router.push('/login')
        }
        ElMessage.error('登录已过期，请重新登录')
      } else {
        ElMessage.error(error.message || '网络错误')
      }
      return Promise.reject(error)
    }
  )

  return request
}
