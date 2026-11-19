import { client, request } from '@/api/http'
import type { ImportCommitResult, ImportJob, ImportJobSummary } from '@/types/import'

/** I1 上传一个样册 PDF，服务端解析后生成 Excel。 */
export function createImportJob(file: File): Promise<ImportJob> {
  const form = new FormData()
  form.append('file', file)
  // 走统一封装：网络层失败（后端未启动/重启中/超时）也能给出可读提示，
  // 而不是笼统的「转换失败」。大样册（324 页 / 26MB）同步解析约 5s，超时放宽到 180s。
  return request<ImportJob>({
    url: '/imports',
    method: 'POST',
    data: form,
    timeout: 180_000,
  })
}

/** I2 任务列表（摘要）。 */
export function listImportJobs(): Promise<ImportJobSummary[]> {
  return request<ImportJobSummary[]>({ url: '/imports', method: 'GET' })
}

/** I3 任务详情。 */
export function getImportJob(jobId: string): Promise<ImportJob> {
  return request<ImportJob>({ url: `/imports/${jobId}`, method: 'GET' })
}

/** I4 删除任务（连同原 PDF 与产物）。 */
export function deleteImportJob(jobId: string): Promise<void> {
  return request<void>({ url: `/imports/${jobId}`, method: 'DELETE' })
}

/** I5 下载生成的 Excel。 */
export async function exportImportJob(jobId: string, fileName: string): Promise<void> {
  const response = await client.get(`/imports/${jobId}/export`, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data as Blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName || `import-${jobId}.xlsx`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/**
 * I6 入库：把该任务 Excel 里的电机写进电机库（型号名 + 配置名相同即整体覆盖）。
 *
 * 逐台独立处理，返回逐台结果；必填不全的电机只跳过，不影响其他台。
 */
export function commitImportJob(jobId: string): Promise<ImportCommitResult> {
  return request<ImportCommitResult>({
    url: `/imports/${jobId}/commit`,
    method: 'POST',
    timeout: 120_000,
  })
}
