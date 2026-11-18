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
