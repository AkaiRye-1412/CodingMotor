/** 跨模块共享的领域枚举（取值与后端枚举 / DB CHECK 约束一致）。 */

export type AxisKind = 'linear' | 'rotary'

export type MotionMode = 'pointToPoint' | 'custom'

export type CurveShape = 'trapezoid' | 'triangle'

export type MotorTypeCode = 'linear' | 'torque' | 'coil'

export type EntryMethod = '手工录入' | '识别草稿'
