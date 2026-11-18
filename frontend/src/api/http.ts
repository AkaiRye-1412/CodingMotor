import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ApiError, type ApiEnvelope, type ErrorsPayload } from '@/types/api'

/**
 * 统一请求封装（04 §1.4）：
 *   - baseURL 固定 /api，开发态由 Vite proxy 转发；
 *   - 解包 {code,message,data}，成功返回 data，失败抛出 ApiError；
 *   - 保留 axios 的取消异常（AbortController）供 useCalculation 静默忽略。
 */
const client = axios.create({
  baseURL: '/api',
  timeout: 60_000,
})

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await client.request<ApiEnvelope<T>>(config)
    const envelope = response.data
    if (envelope.code !== 'OK') {
      const errors = (envelope.data as ErrorsPayload | null)?.errors ?? []
      throw new ApiError(envelope.code, envelope.message, errors)
    }
    return envelope.data as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (axios.isCancel(error)) throw error

    if (error instanceof AxiosError) {
      const body = error.response?.data as Partial<ApiEnvelope<ErrorsPayload>> | undefined
      if (body?.code) {
        throw new ApiError(body.code, body.message ?? '请求失败', body.data?.errors ?? [])
      }
      throw new ApiError('NETWORK_ERROR', '无法连接本地服务，请确认应用已启动')
    }
    throw error
  }
}

export { client }
