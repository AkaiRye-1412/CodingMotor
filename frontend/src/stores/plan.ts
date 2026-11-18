import { defineStore } from 'pinia'
import type { AxisKind } from '@/types/domain'

export interface PlanDraft {
  id: number
  /** 直线：总移动质量 kg；旋转：负载惯量 kg·m²。 */
  load: number | null
  distance: number | null
  time: number | null
  dwell: number
  maxVelocity: number | null
  maxAcceleration: number | null
  resistance: number
  tiltDeg: number
  direction: 'up' | 'down'
  ambientTemp: number
  supplyVoltage: number | null
}

export interface PlanBook {
  plans: PlanDraft[]
  currentIndex: number
}

let nextPlanId = 1

function createEmptyPlan(): PlanDraft {
  return {
    id: nextPlanId++,
    load: null,
    distance: null,
    time: null,
    dwell: 0,
    maxVelocity: null,
    maxAcceleration: null,
    resistance: 0,
    tiltDeg: 0,
    direction: 'up',
    ambientTemp: 22,
    // 默认供电母线 310 VDC（用户已确认；留空仍可在输入框中清除）
    supplyVoltage: 310,
  }
}

function createBook(): PlanBook {
  return { plans: [createEmptyPlan()], currentIndex: 0 }
}

/**
 * M3：方案管理。每个页签维护独立方案列表（直线 m / 旋转 ° 不可混用）。
 * 删除到 1 条时重置为空方案（01 §3.2 M3）。
 */
export const usePlanStore = defineStore('plan', {
  state: () => ({
    books: {
      linear: createBook(),
      rotary: createBook(),
    } as Record<AxisKind, PlanBook>,
  }),
  actions: {
    book(axis: AxisKind): PlanBook {
      return this.books[axis]
    },
    addPlan(axis: AxisKind) {
      const book = this.books[axis]
      book.plans.push(createEmptyPlan())
      book.currentIndex = book.plans.length - 1
    },
    removeCurrentPlan(axis: AxisKind) {
      const book = this.books[axis]
      if (book.plans.length === 1) {
        book.plans = [createEmptyPlan()]
        book.currentIndex = 0
        return
      }
      book.plans.splice(book.currentIndex, 1)
      if (book.currentIndex >= book.plans.length) {
        book.currentIndex = book.plans.length - 1
      }
    },
    goTo(axis: AxisKind, index: number) {
      const book = this.books[axis]
      if (index >= 0 && index < book.plans.length) {
        book.currentIndex = index
      }
    },
    previous(axis: AxisKind) {
      this.goTo(axis, this.books[axis].currentIndex - 1)
    },
    next(axis: AxisKind) {
      this.goTo(axis, this.books[axis].currentIndex + 1)
    },
  },
})
