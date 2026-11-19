import { computed, watch } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { useMotorStore } from '@/stores/motor'
import { usePlanStore, type PlanDraft } from '@/stores/plan'
import { useProjectStore } from '@/stores/project'
import { useSettingStore } from '@/stores/setting'
import type { CalculationRequest } from '@/types/calculation'
import { positiveOrNull } from '@/utils/format'

/**
 * 把「项目/页签/当前方案/余量/过滤/选中电机」组合成 B1 请求，
 * 并在任一输入变化时交给 K4 调度器（150ms 防抖 + 取消 + 序号）。
 *
 * <p>输入不足（未填负载/距离/必要时间）时不发请求，避免启动期出现误报。
 */
export function useAutoCalculation() {
  const project = useProjectStore()
  const setting = useSettingStore()
  const planStore = usePlanStore()
  const motorStore = useMotorStore()
  const scheduler = useCalculation()

  const currentBook = computed(() => planStore.books[project.axisKind])
  const currentPlan = computed<PlanDraft>(() => {
    const book = currentBook.value
    return book.plans[book.currentIndex] ?? book.plans[0]
  })

  // 先注册页签切换清理：过滤器与选中电机不能跨量纲复用
  watch(
    () => project.axisKind,
    () => {
      motorStore.selectMotor(null)
      setting.clearFilters()
      scheduler.reset()
    },
  )

  const request = computed<CalculationRequest | null>(() => {
    const plan = currentPlan.value
    if (!isReady(plan, setting.mode)) {
      return null
    }

    return {
      axisKind: project.axisKind,
      mode: setting.mode,
      shape: setting.mode === 'pointToPoint' ? setting.shape : undefined,
      smooth: { enabled: setting.smoothEnabled, tauMs: setting.smoothTauMs },
      plan: {
        load: plan.load as number,
        distance: plan.distance as number,
        time: positiveOrNull(plan.time),
        dwell: plan.dwell ?? 0,
        maxVelocity: positiveOrNull(plan.maxVelocity),
        maxAcceleration: positiveOrNull(plan.maxAcceleration),
        resistance: plan.resistance ?? 0,
        tiltDeg: plan.tiltDeg ?? 0,
        ambientTemp: plan.ambientTemp ?? 22,
        supplyVoltage:
          plan.supplyVoltage != null && Number.isFinite(plan.supplyVoltage) && plan.supplyVoltage >= 0
            ? plan.supplyVoltage
            : null,
      },
      margin: {
        lowerPercent: setting.marginLowerPercent,
        upperPercent: setting.marginUpperPercent,
        upperEnabled: setting.marginUpperEnabled,
      },
      filters: {
        structures: [...setting.filterStructures],
        coolings: [],
      },
      selectedMotorId: motorStore.selectedMotorId,
    }
  })

  watch(
    request,
    (payload) => {
      if (payload === null) {
        scheduler.reset()
      } else {
        scheduler.schedule(payload)
      }
    },
    { deep: true, immediate: true },
  )

  return { scheduler, request, currentPlan }
}

function isReady(plan: PlanDraft, mode: string): boolean {
  if (plan.load == null || !Number.isFinite(plan.load) || plan.load < 0) {
    return false
  }
  if (plan.distance == null || !Number.isFinite(plan.distance) || plan.distance <= 0) {
    return false
  }
  if (mode === 'pointToPoint') {
    return positiveOrNull(plan.time) !== null
  }
  return (
    positiveOrNull(plan.time) !== null
    || positiveOrNull(plan.maxVelocity) !== null
    || positiveOrNull(plan.maxAcceleration) !== null
  )
}
