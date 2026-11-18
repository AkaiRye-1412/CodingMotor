import { defineStore } from 'pinia'
import type { AxisKind } from '@/types/domain'

function today(): string {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/**
 * M1：项目 / 轴信息。会话级状态，不持久化（Q3=A）。
 * 轴名称单框共享，两个页签共用（I-13）。
 */
export const useProjectStore = defineStore('project', {
  state: () => ({
    projectName: 'General Machine',
    axisName: 'Axis 1',
    projectDate: today(),
    axisKind: 'linear' as AxisKind,
  }),
  actions: {
    switchAxisKind(kind: AxisKind) {
      this.axisKind = kind
    },
  },
})
