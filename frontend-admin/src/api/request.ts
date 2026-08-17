import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { AxiosRequestConfig } from 'axios'
import { createRequest } from '@shared/js/createRequest'
import router from '../router'
import type { Result } from './types'

const request = createRequest({ tokenKey: 'admin-token', router, axios, ElMessage })

/**
 * 类型化请求包装
 *
 * 响应拦截器（shared/js/createRequest）已把 AxiosResponse 解包为 Result 结构，
 * 这里用泛型包装避免每个 api 方法内强转，返回类型即业务数据 Result<T>。
 * （本批叠加式接入 TypeScript，仍保留 Element Plus ElMessage 错误提示）
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