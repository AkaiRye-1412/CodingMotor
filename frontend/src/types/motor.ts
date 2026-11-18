import type { EntryMethod, MotorTypeCode } from '@/types/domain'

export interface MotorListItem {
  motorId: number
  modelName: string
  configName: string
  motorType: MotorTypeCode
  structure: string
  cooling: string
  bore: string | null
  manufacturer: string | null
  series: string | null
  entryMethod: EntryMethod
  enteredOn: string | null
  updatedAt: string
}

/** 服务端派生项：只读、不入库、不参与计算（04 §1.3.1）。 */
export interface MotorDerived {
  prated: number
  pmax: number
  kmDerived: number
}

/** 规格对象随 motorType 变化；字段名与 DB 列一一对应（04 附录 A）。 */
export type MotorSpec = Record<string, number | string | null>

export type MotorSpecWithDerived = MotorSpec & { derived?: MotorDerived }

export interface MotorDetail extends MotorListItem {
  sourceDoc: string | null
  sourcePage: string | null
  note: string | null
  createdAt: string
  spec: MotorSpecWithDerived
}

export interface MotorUpsertRequest {
  modelName: string
  configName: string
  motorType: MotorTypeCode
  structure: string
  cooling: string
  bore: string | null
  manufacturer: string | null
  series: string | null
  sourceDoc: string | null
  sourcePage: string | null
  entryMethod: EntryMethod
  enteredOn: string | null
  note: string | null
  spec: MotorSpec
}

export interface CreatedMotor {
  motorId: number
}

export interface MotorListQuery {
  page?: number
  size?: number
  motorType?: MotorTypeCode
  keyword?: string
  structure?: string
  cooling?: string
}
