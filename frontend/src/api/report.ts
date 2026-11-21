import axios, { AxiosError } from 'axios'
import { client, request } from '@/api/http'
import { ApiError, type ApiEnvelope, type ErrorsPayload } from '@/types/api'
import type { ReportRequest, ReportSaveResult } from '@/types/report'

/**
 * D1：导出 PDF。
 *
 * <p>成功返回二进制流；失败时后端仍返回统一 JSON 信封，需要按 Content-Type 判别（04 §1.4.2）。
 */
export async function exportReportPdf(payload: ReportRequest): Promise<Blob> {
  try {
    const response = await client.post('/reports/pdf', payload, {
      responseType: 'blob',
      timeout: 120_000,
    })
    return response.data as Blob
  } catch (error) {
    if (axios.isCancel(error)) throw error

    if (error instanceof AxiosError && error.response?.data instanceof Blob) {
      const text = await error.response.data.text()
      try {
        const body = JSON.parse(text) as ApiEnvelope<ErrorsPayload>
        if (body.code) {
          throw new ApiError(body.code, body.message, body.data?.errors ?? [])
        }
      } catch (parseError) {
        if (parseError instanceof ApiError) throw parseError
      }
    }
    if (error instanceof ApiError) throw error
    throw new ApiError('REPORT_EXPORT_FAILED', '报告导出失败，请确认应用已启动后重试')
  }
}

/**
 * 「另存为」导出：服务端弹系统原生对话框让用户自己选路径，再把 PDF 写到那里。
 *
 * <p>timeout 设为 0（不超时）—— 请求会一直挂着等用户在对话框里选完路径，
 * 按默认 60s 掐断会把正常操作误判成失败。
 * 无图形环境时后端返回 `REPORT_SAVE_UNAVAILABLE`，调用方应回退到 {@link exportReportPdf}。
 */
export async function exportReportToChosenPath(
  payload: ReportRequest,
): Promise<ReportSaveResult> {
  return request<ReportSaveResult>({
    url: '/reports/pdf/save',
    method: 'post',
    data: payload,
    timeout: 0,
  })
}

