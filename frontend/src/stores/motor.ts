import { defineStore } from 'pinia'
import { getMotorTemplates } from '@/api/template'
import { ApiError } from '@/types/api'
import type { TemplateResponse } from '@/types/template'

/**
 * K2 模板缓存 + C-A 选中电机的唯一写入口。
 *
 * <p>模板加载失败要阻塞启动（04 §1.4.2 加载态约定），因此 action 在设置错误后重新抛出，
 * 由 App.vue 阻断进入主界面并提供重试。
 */
export const useMotorStore = defineStore('motor', {
  state: () => ({
    templates: null as TemplateResponse | null,
    templatesLoading: false,
    templatesError: '',
    selectedMotorId: null as number | null,
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
    selectMotor(motorId: number | null) {
      this.selectedMotorId = motorId
    },
  },
})
