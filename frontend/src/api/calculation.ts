import { request } from '@/api/http'
import type { CalculationRequest, CalculationResponse } from '@/types/calculation'

/**
 * B1：执行计算。一次请求返回规划 + 理论 + 判定 + 应用值 + 采样（Q2-a）。
 * signal 用于 useCalculation 的取消语义（A-2）。
 */
export function runCalculation(
  payload: CalculationRequest,
  signal?: AbortSignal,
): Promise<CalculationResponse> {
  return request<CalculationResponse>({
    url: '/calculations',
    method: 'POST',
    data: payload,
    signal,
  })
}
