import { defineStore } from 'pinia'
import { getMotor } from '@/api/motor'
import { getMotorTemplates } from '@/api/template'
import { ApiError } from '@/types/api'
import type { TemplateResponse } from '@/types/template'

/**
 * K2 模板缓存 + C-A 选中电机的唯一写入口。
 *
 * <p>模板加载失败要阻塞启动（04 §1.4.2 加载态约定），因此 action 在设置错误后重新抛出，
 * 由 App.vue 阻断进入主界面并提供重试。
 */

/** 选中电机的「自身质量/惯量」取值：力矩取转动惯量，直线/音圈取线圈质量（CAL-26）。 */
function selfLoadOf(motorType: string, spec: Record<string, number | string | null> | undefined): number {
  const raw = motorType === 'torque' ? spec?.inertia : spec?.moverMass
  const value = typeof raw === 'string' ? Number(raw) : raw
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0
}

/** 选中序号：连续点选时丢弃过期请求的结果（避免上一台电机的质量覆盖当前值）。 */
let selectSeq = 0

export const useMotorStore = defineStore('motor', {
  state: () => ({
    templates: null as TemplateResponse | null,
    templatesLoading: false,
    templatesError: '',
    selectedMotorId: null as number | null,
    /**
     * 选中电机自身质量/惯量（kg 或 kg·m²）。未选电机为 0。
     *
     * <p>只读派生值：由 {@link selectMotor} 取回电机详情后写入，M4 只展示不允许编辑（CAL-26）。
     */
    selectedMotorMass: 0,
    /** 正在读取选中电机的质量/惯量。 */
    motorMassLoading: false,
  }),
  getters: {
    templatesReady(state): boolean {
      return state.templates !== null
    },
  },
  actions: {
    async loadTemplates() {
      this.templatesLoading = true
      this.templatesError = ''
      try {
        this.templates = await getMotorTemplates()
      } catch (error) {
        this.templatesError =
          error instanceof ApiError ? error.message : '字段模板加载失败，请重试'
        throw error
      } finally {
        this.templatesLoading = false
      }
    },
    /**
     * 选中电机（C-A 唯一入口），并异步带出它的线圈质量 / 转动惯量。
     *
     * <p>换电机时先把质量清 0，避免用上一台电机的质量算一次推力；
     * 详情回来后再填（CAL-26）。
     */
    async selectMotor(motorId: number | null) {
      this.selectedMotorId = motorId
      const seq = ++selectSeq
      if (motorId == null) {
        this.selectedMotorMass = 0
        this.motorMassLoading = false
        return
      }
      this.selectedMotorMass = 0
      this.motorMassLoading = true
      try {
        const motor = await getMotor(motorId)
        if (seq !== selectSeq) return
        this.selectedMotorMass = selfLoadOf(motor.motorType, motor.spec)
      } catch {
        if (seq !== selectSeq) return
        this.selectedMotorMass = 0
      } finally {
        if (seq === selectSeq) this.motorMassLoading = false
      }
    },
  },
})