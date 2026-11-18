/** 统一响应信封与分页（04 §1.1 / §1.2）。 */

export interface ApiEnvelope<T> {
  code: string
  message: string
  data: T | null
}

export interface PageResult<T> {
  page: number
  size: number
  total: number
  totalPages: number
  items: T[]
}

/** 校验失败时 data.errors[] 的单项（04 §1.3）。 */
export interface FieldError {
  field: string
  reason: string
}

export interface ErrorsPayload {
  errors: FieldError[]
}

/**
 * 业务错误：由 api/http.ts 从统一信封解包后抛出。
 * 页面按 code / message / errors 做提示与字段定位。
 */
export class ApiError extends Error {
  readonly code: string
  readonly errors: FieldError[]

  constructor(code: string, message: string, errors: FieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.errors = errors
  }
}
