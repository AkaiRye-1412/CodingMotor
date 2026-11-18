import type { MotorUpsertRequest } from '@/types/motor'

/** 任务状态机（06 §3）。 */
export type JobStatus = 'UPLOADED' | 'PARSED' | 'COMMITTED' | 'CANCELLED'

/** 页面级解析状态。 */
export type PageStatus = 'PENDING' | 'RECOGNIZED' | 'EMPTY' | 'OCR_REQUIRED' | 'FAILED'

/** 上传文件登记。 */
export interface ImportFileRef {
  fileId: string
  name: string
  kind: 'pdf' | 'image'
  pages: number
}

/** 页面 = 解析单元。 */
export interface ImportPage {
  pageId: string
  fileId: string
  pageNumber: number
  status: PageStatus
  message: string | null
  textLineCount: number
}

/** 草稿校验问题。 */
export interface DraftIssue {
  field: string
  reason: string
}

/** 样册原始值快照。 */
export interface RawField {
  value: string
  unit: string
  text: string
}

/** 识别草稿。 */
export interface Draft {
  draftId: string
  pageId: string
  sourcePage: string
  confidence: number
  request: MotorUpsertRequest
  rawFields: Record<string, RawField>
  issues: DraftIssue[]
  checked: boolean
}

/** 导入任务。 */
export interface ImportJob {
  jobId: string
  status: JobStatus
  createdAt: string
  files: ImportFileRef[]
  pages: ImportPage[]
  drafts: Draft[]
}

/** I2 任务列表摘要。 */
export interface ImportJobSummary {
  jobId: string
  status: JobStatus
  fileCount: number
  pageParsed: number
  draftCount: number
  createdAt: string
  remainingDays: number
}

/** I12 单条提交结果。 */
export interface CommitItemResult {
  draftId: string
  ok: boolean
  motorId: number | null
  errorCode: string | null
  message: string
  issues: DraftIssue[]
}

/** I12 批量提交结果。 */
export interface CommitResult {
  jobId: string
  total: number
  succeeded: number
  failed: number
  items: CommitItemResult[]
}

/** 置信度标红阈值（Q5）。 */
export const CONFIDENCE_WARN = 0.6
