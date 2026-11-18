import { request } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  CreatedMotor,
  MotorDetail,
  MotorListItem,
  MotorListQuery,
  MotorUpsertRequest,
} from '@/types/motor'

/** C1：电机库分页列表。 */
export function listMotors(query: MotorListQuery = {}): Promise<PageResult<MotorListItem>> {
  return request<PageResult<MotorListItem>>({ url: '/motors', method: 'GET', params: query })
}

/** C2：电机详情（含 spec 与服务端派生的 derived）。 */
export function getMotor(motorId: number): Promise<MotorDetail> {
  return request<MotorDetail>({ url: `/motors/${motorId}`, method: 'GET' })
}

/** C3：新增电机（主表 + 规格同一事务）。 */
export function createMotor(payload: MotorUpsertRequest): Promise<CreatedMotor> {
  return request<CreatedMotor>({ url: '/motors', method: 'POST', data: payload })
}

/** C4：整体替换电机记录（不允许改 motorType）。 */
export function updateMotor(motorId: number, payload: MotorUpsertRequest): Promise<void> {
  return request<void>({ url: `/motors/${motorId}`, method: 'PUT', data: payload })
}
