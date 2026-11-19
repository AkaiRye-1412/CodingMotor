<script setup lang="ts">
import { computed } from 'vue'
import MotionChart from '@/charts/MotionChart.vue'
import { useCalculation } from '@/composables/useCalculation'
import { usePlanStore } from '@/stores/plan'
import { useProjectStore } from '@/stores/project'
import { useSettingStore } from '@/stores/setting'

/** M9：运动曲线（速度-时间 / 力·力矩-时间），图表保持自研 Canvas 视觉（5.2.1）。 */
const project = useProjectStore()
const setting = useSettingStore()
const planStore = usePlanStore()
const { result, isCalculating } = useCalculation()

const response = computed(() => (result.value?.stage.ok ? result.value : null))

const currentPlan = computed(() => {
  const book = planStore.books[project.axisKind]
  return book.plans[book.currentIndex] ?? book.plans[0]
})

const speedPoints = computed(() =>
  (response.value?.sampling?.speed ?? []).map((point) => ({ t: point.t, value: point.v })),
)

const forcePoints = computed(() =>
  (response.value?.sampling?.force ?? []).map((point) => ({ t: point.t, value: point.f })),
)

const stageLines = computed(() => {
  const curve = response.value?.curve
  if (!curve) return []
  const cruiseEnd = curve.tAccel + Math.max(curve.tCruise, 0)
  return [curve.tAccel, cruiseEnd]
})

const velocityUnit = computed(() => response.value?.units?.velocity ?? '')
const forceUnit = computed(() => response.value?.units?.force ?? '')

const speedReferences = computed(() => {
  if (setting.mode !== 'custom') return []
  const limit = currentPlan.value.maxVelocity
  if (limit == null || !Number.isFinite(limit) || limit <= 0) return []
  return [{ value: limit, label: '用户速度上限', color: '#e6a23c' }]
})


</script>

<template>
  <div class="curve-panel" :class="{ 'is-calculating': isCalculating }">
    <MotionChart
      caption="图 1：速度 / 时间图"
      :points="speedPoints"
      :y-unit="velocityUnit"
      color="#409eff"
      :reference-lines="speedReferences"
      :stage-lines="stageLines"
    />
    <MotionChart
      caption="图 2：推力 / 力矩 / 时间图"
      :points="forcePoints"
      :y-unit="forceUnit"
      color="#e6a23c"
      :stage-lines="stageLines"
    />
  </div>
</template>

<style scoped>
.curve-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: opacity 0.2s ease;
}

.curve-panel.is-calculating {
  opacity: 0.55;
}
</style>
