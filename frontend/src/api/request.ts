import axios from 'axios'
import { createDiscreteApi } from 'naive-ui/es/discrete'
import { createRequest } from '@shared/js/createRequest'
import router from '../router'
import type { AxiosRequestConfig } from 'axios'
import type { Result } from './types'

// C9：用户端已移除旧组件库依赖，拦截器错误提示改用 Naive 的离散式 message
//（createDiscreteApi 独立于 n-message-provider 渲染，可在模块作用域安全使用；
//  createRequest 接口的 ElMessage 选项键接收的即是此 message 实例）
const { message } = createDiscreteApi(['message'])

const request = createRequest({ tokenKey: 'token', router, axios, ElMessage: message })

/**
 * 类型化请求包装
 *
 * 响应拦截器（shared/js/createRequest）已把 AxiosResponse 解包为 Result 结构，
 * 这里用泛型包装避免每个 api 方法内强转，返回类型即业务数据 Result<T>。
 */
export function get<T>(url: string, config?: AxiosRequestConfig): Promise<Result<T>> {
  return request.get(url, config) as unknown as Promise<Result<T>>
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<Result<T>> {
  return request.post(url, data, config) as unknown as Promise<Result<T>>
}

export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<Result<T>> {
  return request.put(url, data, config) as unknown as Promise<Result<T>>
}

export function del<T>(url: string, config?: AxiosRequestConfig): Promise<Result<T>> {
  return request.delete(url, config) as unknown as Promise<Result<T>>
}

export default request
