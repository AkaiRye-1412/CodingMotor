import { defineStore } from 'pinia'
import type { CurveShape, MotionMode } from '@/types/domain'

/**
 * M2 + M5 + M7 过滤：页签级设置。
 * 模式/形状/S 曲线/余量/结构冷却过滤均随 B1 请求发送。
 */
export const useSettingStore = defineStore('setting', {
  state: () => ({
    mode: 'pointToPoint' as MotionMode,
    shape: 'trapezoid' as CurveShape,
    smoothEnabled: false,
    smoothTauMs: 16,
    marginLowerPercent: 30,
    marginUpperPercent: 300,
    marginUpperEnabled: false,
    filterStructures: [] as string[],
    /** 第 69 轮：「结构」（冷却方式）筛选，可选值见 COOLING_OPTIONS。 */
    filterCoolings: [] as string[],
  }),
  actions: {
    toggleFilter(value: string) {
      const index = this.filterStructures.indexOf(value)
      if (index >= 0) {
        this.filterStructures.splice(index, 1)
      } else {
        this.filterStructures.push(value)
      }
    },
    toggleCooling(value: string) {
      const index = this.filterCoolings.indexOf(value)
      if (index >= 0) {
        this.filterCoolings.splice(index, 1)
      } else {
        this.filterCoolings.push(value)
      }
    },
    clearFilters() {
      this.filterStructures = []
      this.filterCoolings = []
    },
  },
})

/** 「结构」（冷却方式）选项，顺序与后端 MotorCooling.OPTIONS 一致。 */
export const COOLING_OPTIONS = ['自然冷却', '气冷', '水冷'] as const
