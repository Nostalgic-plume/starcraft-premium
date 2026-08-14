import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { Router } from 'vue-router'

export interface CreateRequestOptions {
  tokenKey: string
  router: Router | null
  axios: typeof import('axios').default
  ElMessage: { error(msg: string): void }
}

export function createRequest({ tokenKey, router, axios, ElMessage }: CreateRequestOptions): AxiosInstance {
  const request = axios.create({ baseURL: '/api', timeout: 15000 })

  request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem(tokenKey)
    if (token) config.headers.set('token', token)
    return config
  })

  request.interceptors.response.use(
    (res: AxiosResponse<{ code: number; msg: string; data?: unknown }>) => {
      const data = res.data
      if (data.code && data.code !== 200) {
        ElMessage.error(data.msg || '请求失败')
        return Promise.reject(new Error(data.msg))
      }
      return res.data as any // M2 引入 API 类型后细化为泛型
    },
    (error: { message?: string; response?: { status: number } }) => {
      if (error.response?.status === 401) {
        localStorage.removeItem(tokenKey)
        router?.push('/login')
        ElMessage.error('登录已过期，请重新登录')
      } else {
        ElMessage.error(error.message || '网络错误')
      }
      return Promise.reject(error)
    }
  )
  return request
}