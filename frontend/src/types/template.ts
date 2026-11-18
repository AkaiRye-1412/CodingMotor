import type { MotorTypeCode } from '@/types/domain'

/** 字段出现位置：detail = M8 参数表，form = M10 录入表单（04 §3 A1）。 */
export type FieldDisplay = 'detail' | 'form'

export interface FieldSpec {
  key: string
  label: string
  unit: string
  /** 入库门槛 = 准入判定 = 星标的唯一来源（C-B）。 */
  required: boolean
  /** 录入单位 → 内部单位换算系数（音圈质量 0.001）。 */
  factor: number
  display: FieldDisplay[]
}

export interface FieldGroup {
  title: string
  /** 该组是否允许在录入表单中“新增选填项”。 */
  optionalAdd: boolean
  fields: FieldSpec[]
}

export interface TypeTemplate {
  label: string
  configs: string[]
  structures: string[]
  coolings: string[]
  bores: string[]
  groups: FieldGroup[]
}

export interface TemplateResponse {
  types: MotorTypeCode[]
  templates: Record<MotorTypeCode, TypeTemplate>
}
