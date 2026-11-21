import type { CalculationRequest } from '@/types/calculation'
import type { AxisKind } from '@/types/domain'

export interface ReportSettings {
  mode: CalculationRequest['mode']
  smooth: CalculationRequest['smooth']
  margin: CalculationRequest['margin']
}

export interface ReportPlanItem {
  planNo: number
  plan: CalculationRequest['plan']
  shape?: CalculationRequest['shape']
  selectedMotorId: number | null
  /** 界面「方向（上/下）」单选：只影响报告展示（计算只看 tiltDeg）。 */
  direction?: 'up' | 'down'
}

export interface ReportRequest {
  projectName: string
  axisName: string
  reportDate: string
  axisKind: AxisKind
  customerName?: string
  workingConditionNote?: string
  settings: ReportSettings
  plans: ReportPlanItem[]
}

/** 「另存为」结果：saved=false 表示用户在系统对话框里取消了保存。 */
export interface ReportSaveResult {
  saved: boolean
  path?: string
  filename?: string
}
