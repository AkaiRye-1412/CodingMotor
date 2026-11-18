import axios, { AxiosError } from 'axios'
import { client } from '@/api/http'
import { ApiError, type ApiEnvelope, type ErrorsPayload } from '@/types/api'
import type { ReportRequest } from '@/types/report'

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
