import { ref, shallowRef, type Ref, type ShallowRef } from 'vue'
import { runCalculation } from '@/api/calculation'
import { ApiError } from '@/types/api'
import type { CalculationRequest, CalculationResponse } from '@/types/calculation'

/** R-5 裁定：输入变化 150ms 防抖；滑块拖动 80ms 节流待滑块接线时补。 */
const DEBOUNCE_MS = 150

export interface CalculationScheduler {
  result: ShallowRef<CalculationResponse | null>
  isCalculating: Ref<boolean>
  error: ShallowRef<ApiError | null>
  schedule: (payload: CalculationRequest) => void
  cancel: () => void
  reset: () => void
}

/**
 * K4：重算调度器（全应用单例）。
 *
 * <p>所有面板共享同一份结果/加载/错误状态，避免各组件各自请求导致状态分叉。
 * 职责：防抖、取消上一次请求（AbortController）、请求序号比对丢弃过期响应（A-2）。
 */
let sharedScheduler: CalculationScheduler | null = null

export function useCalculation(): CalculationScheduler {
  if (sharedScheduler === null) {
    sharedScheduler = createScheduler()
  }
  return sharedScheduler
}

function createScheduler(): CalculationScheduler {
  const result = shallowRef<CalculationResponse | null>(null)
  const isCalculating = ref(false)
  const error = shallowRef<ApiError | null>(null)

  let timer: ReturnType<typeof setTimeout> | null = null
  let controller: AbortController | null = null
  let issuedSeq = 0

  /** 输入变化时调用；同一轮连续输入只执行最后一次。 */
  function schedule(payload: CalculationRequest) {
    issuedSeq += 1
    const seq = issuedSeq
    if (timer !== null) {
      clearTimeout(timer)
    }
    timer = setTimeout(() => {
      void execute(payload, seq)
    }, DEBOUNCE_MS)
  }

  async function execute(payload: CalculationRequest, seq: number) {
    controller?.abort()
    controller = new AbortController()
    const signal = controller.signal

    isCalculating.value = true
    error.value = null
    try {
      const response = await runCalculation(payload, signal)
      // 过期响应直接丢弃，避免乱序覆盖最新结果
      if (seq !== issuedSeq) return
      result.value = response
    } catch (caught) {
      // 主动取消或已有更新的请求时静默退出
      if (signal.aborted || seq !== issuedSeq) return
      error.value =
        caught instanceof ApiError ? caught : new ApiError('CALC_UNKNOWN', '计算请求失败')
    } finally {
      if (seq === issuedSeq) {
        isCalculating.value = false
      }
    }
  }

  /** 页签切换等场景主动中止在途请求，但保留上一次结果。 */
  function cancel() {
    issuedSeq += 1
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    controller?.abort()
    controller = null
    isCalculating.value = false
  }

  /** 输入不足以计算时取消并清空结果，避免展示过期数据。 */
  function reset() {
    cancel()
    result.value = null
    error.value = null
  }

  return { result, isCalculating, error, schedule, cancel, reset }
}
