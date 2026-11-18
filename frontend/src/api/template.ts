import { request } from '@/api/http'
import type { TemplateResponse } from '@/types/template'

/** A1：三套字段模板一次下发（前端启动时拉取并缓存）。 */
export function getMotorTemplates(): Promise<TemplateResponse> {
  return request<TemplateResponse>({ url: '/motor-templates', method: 'GET' })
}
