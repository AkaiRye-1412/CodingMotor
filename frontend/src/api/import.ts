import { client, request } from '@/api/http'
import type {
  CommitResult,
  Draft,
  DraftIssue,
  ImportJob,
  ImportJobSummary,
  ImportPage,
} from '@/types/import'
import type { MotorUpsertRequest } from '@/types/motor'

/** I1 创建任务（上传 PDF/图片，multipart）。 */
export async function createImportJob(files: File[]): Promise<ImportJob> {
  const form = new FormData()
  for (const f of files) form.append('files', f)
  const response = await client.post<ApiEnvelope<ImportJob>>('/imports', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return response.data.data as ImportJob
}

/** I2 任务列表（摘要）。 */
export function listImportJobs(): Promise<ImportJobSummary[]> {
  return request<ImportJobSummary[]>({ url: '/imports', method: 'GET' })
}

/** I3 任务详情。 */
export function getImportJob(jobId: string): Promise<ImportJob> {
  return request<ImportJob>({ url: `/imports/${jobId}`, method: 'GET' })
}

/** I4 取消/清理任务。 */
export function deleteImportJob(jobId: string): Promise<void> {
  return request<void>({ url: `/imports/${jobId}`, method: 'DELETE' })
}

/** I5 页面列表。 */
export function listImportPages(jobId: string): Promise<ImportPage[]> {
  return request<ImportPage[]>({ url: `/imports/${jobId}/pages`, method: 'GET' })
}

/** I6 页面提取文本。 */
export function getImportPageText(jobId: string, pageId: string): Promise<string[]> {
  return request<string[]>({ url: `/imports/${jobId}/pages/${pageId}/text`, method: 'GET' })
}

/** I7 解析单页。 */
export function recognizePage(jobId: string, pageId: string): Promise<Draft[]> {
  return request<Draft[]>({ url: `/imports/${jobId}/pages/${pageId}/recognize`, method: 'POST' })
}

/** I8 草稿列表。 */
export function listDrafts(jobId: string, pageId?: string): Promise<Draft[]> {
  return request<Draft[]>({
    url: `/imports/${jobId}/drafts`,
    method: 'GET',
    params: pageId ? { pageId } : undefined,
  })
}

/** I9 编辑草稿。 */
export function editDraft(jobId: string, draftId: string, payload: MotorUpsertRequest): Promise<Draft> {
  return request<Draft>({ url: `/imports/${jobId}/drafts/${draftId}`, method: 'PUT', data: payload })
}

/** I10 删除草稿。 */
export function deleteDraft(jobId: string, draftId: string): Promise<void> {
  return request<void>({ url: `/imports/${jobId}/drafts/${draftId}`, method: 'DELETE' })
}

/** I11 单条校验。 */
export function validateDraft(jobId: string, draftId: string): Promise<DraftIssue[]> {
  return request<DraftIssue[]>({ url: `/imports/${jobId}/drafts/${draftId}/validate`, method: 'POST' })
}

/** I12 批量提交勾选草稿。 */
export function commitJob(jobId: string): Promise<CommitResult> {
  return request<CommitResult>({ url: `/imports/${jobId}/commit`, method: 'POST' })
}

/** I13 勾选/取消勾选。 */
export function setDraftChecked(jobId: string, draftId: string, checked: boolean): Promise<Draft> {
  return request<Draft>({
    url: `/imports/${jobId}/drafts/${draftId}/checked`,
    method: 'POST',
    data: { checked },
  })
}

// http.ts 导出的 ApiEnvelope 类型在此文件按需内联引用
import type { ApiEnvelope } from '@/types/api'
