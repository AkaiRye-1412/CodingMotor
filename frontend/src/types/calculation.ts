import type { AxisKind, CurveShape, MotionMode } from '@/types/domain'

/** B1 请求体（04 §3 B1）。 */
export interface SmoothSetting {
  enabled: boolean
  tauMs: number
}

export interface PlanInput {
  /** 直线：总移动质量 kg；旋转：负载惯量 kg·m²。 */
  load: number
  distance: number
  time?: number | null
  dwell?: number
  maxVelocity?: number | null
  maxAcceleration?: number | null
  resistance?: number
  tiltDeg?: number
  ambientTemp?: number
  supplyVoltage?: number | null
}

export interface MarginSetting {
  lowerPercent: number
  upperPercent?: number
  upperEnabled?: boolean
}

export interface CandidateFilters {
  structures: string[]
  coolings: string[]
}

export interface CalculationRequest {
  axisKind: AxisKind
  mode: MotionMode
  /** 仅点到点有效；自定义模式可省略。 */
  shape?: CurveShape
  smooth: SmoothSetting
  plan: PlanInput
  margin: MarginSetting
  filters: CandidateFilters
  selectedMotorId: number | null
}

/** 工况不可解时也是 HTTP 200，失败信息在 stage 中（04 §1.1 例外）。 */
export interface CalcStage {
  ok: boolean
  code: string
  message: string | null
  details?: Record<string, number>
}

export interface CalcUnits {
  velocity: string
  acceleration: string
  force: string
  distance: string
  time: string
  temperature: string
  voltage: string
}

export interface CalcCurve {
  mode: MotionMode
  shape: CurveShape
  smoothApplied: boolean
  tauMs: number
  /** 实际运动时间（点到点为输入值，自定义为反解值）。 */
  time: number
  peakVelocity: number
  peakAcceleration: number
  peakAccelerationBeforeSmooth: number | null
  accelLiftPercent: number | null
  tAccel: number
  tCruise: number
  tDecel: number
  timeDerived: boolean
  shapeAutoDetected: boolean
  note: string | null
}

export interface TheoreticalResult {
  contForce: number
  peakForce: number
  cycleTime: number
  gravityTerm: number
}

export interface CandidateMotor {
  motorId: number
  modelName: string
  configName: string
  motorType: string
  structure: string
  cooling: string
  /** 列表 Safety（已扣下限门槛），与应用值 safetyPercent 口径不同（C-C）。 */
  headroomRatio: number
  overSized: boolean
  reasons: string[]
  speedCheckSkipped: string[]
}

export interface MotorParameterRow {
  key: string
  label: string
  unit: string
  value: number | null
  derived: boolean
}

export interface SupplyVerdict {
  have: number
  need: number
  remain: number
  pass: boolean
  text: string
}

export interface AppliedValues {
  coilTemp: number | null
  contCurrent: number | null
  peakCurrent: number | null
  busVoltage: number | null
  /** 应用值安全系数（与 headroomRatio 不同名，见 02 C-C）。 */
  safetyPercent: number | null
  contLoadPercent: number | null
  peakLoadPercent: number | null
  thermalVerdict: string
  overBusVoltage: boolean
  supply: SupplyVerdict | null
  missing: string[]
}

export interface SelectedMotorResult {
  motorId: number
  inRecommendation: boolean
  outOfRangeReason: string | null
  parameters: MotorParameterRow[]
  applied: AppliedValues
}

export interface Sampling {
  speed: Array<{ t: number; v: number }>
  force: Array<{ t: number; f: number }>
  pointCount: number
}

export interface CalculationResponse {
  stage: CalcStage
  units: CalcUnits | null
  curve: CalcCurve | null
  theoretical: TheoreticalResult | null
  candidates: CandidateMotor[] | null
  selectedMotor: SelectedMotorResult | null
  sampling: Sampling | null
}
