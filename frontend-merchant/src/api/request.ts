import axios from 'axios'
import { ElMessage } from 'element-plus'
import { createRequest } from '@shared/js/createRequest'
import router from '../router'
import type { AxiosRequestConfig } from 'axios'
import type { Result } from './types'

// M4 商家中心批次1：基础设施 TS 化。
// 商家端本批为「叠加式」，仍保留 Element Plus（现有页面使用 el-*），
// 故拦截器错误提示保留 ElMessage，与 createRequest 的 ElMessage 选项签名兼容；
// 批次2+ 页面重写移除 Element Plus 时再切 Naive 离散式 message（同 admin 批次4）。
const request = createRequest({ tokenKey: 'merchant-token', router, axios, ElMessage })

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