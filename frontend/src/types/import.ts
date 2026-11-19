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
  /** 未识别页中「参数表本身是位图」的页（图片铺满页面 + 出现规格类标题）。 */
  imageTablePages: number[]
  /** 通过 OCR 才解析出参数表的页（数据来自图像识别，需重点核对）。 */
  ocrPages: number[]
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
