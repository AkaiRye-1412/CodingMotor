/** 转换任务状态（06 v2 §3）。 */
export type JobStatus = 'READY' | 'EMPTY' | 'FAILED'

/** 上传文件登记。 */
export interface ImportFileRef {
  fileId: string
  name: string
  kind: 'pdf'
  pages: number
  sizeBytes: number
}

/** 转换统计（06 v2 §4）。 */
export interface ImportStats {
  pageCount: number
  charCount: number
  hasTextLayer: boolean
  parsedPages: number
  tableCount: number
  modelCount: number
  parameterRows: number
  dataRows: number
  footnoteCount: number
  skippedPages: number[]
  excelName: string
  message: string
}

/** 转换任务。 */
export interface ImportJob {
  jobId: string
  status: JobStatus
  createdAt: string
  files: ImportFileRef[]
  stats: ImportStats
}

/** I2 任务列表摘要。 */
export interface ImportJobSummary {
  jobId: string
  status: JobStatus
  fileName: string
  pageCount: number
  tableCount: number
  modelCount: number
  createdAt: string
  remainingDays: number
}
