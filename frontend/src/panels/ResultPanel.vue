<script setup lang="ts">
import { computed } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { formatNumber } from '@/utils/format'

/**
 * M6：理论计算结果 6 张指标卡。
 * 安全余量只在选中电机后出现（读取 applied.safetyPercent），未选电机显示「—」。
 */
interface MetricCard {
  label: string
  value: string
  unit: string
  tone: 'theory' | 'applied'
}

const { result, isCalculating, error } = useCalculation()

const response = computed(() => (result.value?.stage.ok ? result.value : null))
const units = computed(() => response.value?.units ?? null)

const cards = computed<MetricCard[]>(() => {
  const data = response.value
  const applied = data?.selectedMotor?.applied
  return [
    {
      label: '持续力 / 力矩',
      value: formatNumber(data?.theoretical?.contForce, 1),
      unit: units.value?.force ?? '',
      tone: 'theory',
    },
    {
      label: '峰值力 / 力矩',
      value: formatNumber(data?.theoretical?.peakForce, 1),
      unit: units.value?.force ?? '',
      tone: 'theory',
    },
    {
      label: '最大加速度',
      value: formatNumber(data?.curve?.peakAcceleration, 1),
      unit: units.value?.acceleration ?? '',
      tone: 'theory',
    },
    {
      label: '最大速度',
      value: formatNumber(data?.curve?.peakVelocity, 1),
      unit: units.value?.velocity ?? '',
      tone: 'theory',
    },
    {
      label: '安全余量',
      value: applied?.safetyPercent != null ? formatNumber(applied.safetyPercent, 1) : '—',
      unit: '%',
      tone: 'applied',
    },
    {
      label: '总循环周期',
      value: formatNumber(data?.theoretical?.cycleTime, 1),
      unit: 's',
      tone: 'theory',
    },
  ]
})

const message = computed(() => {
  if (error.value) {
    return error.value.message
  }
  const value = result.value
  if (value && !value.stage.ok) {
    return value.stage.message ?? '当前工况无法计算'
  }
  return ''
})

const hasResult = computed(() => response.value !== null)
</script>

<template>
  <div class="result-panel" :class="{ 'is-calculating': isCalculating }">
    <div class="metric-grid">
      <div v-for="card in cards" :key="card.label" class="metric-card">
        <div class="metric-label">{{ card.label }}</div>
        <div :class="['metric-value', card.tone]">
          {{ card.value }}
          <span v-if="card.unit && card.value !== '—'" class="metric-unit">{{ card.unit }}</span>
        </div>
      </div>
    </div>

    <div v-if="message" class="calc-message">
      <el-alert type="warning" :closable="false" :title="message" />
    </div>
    <div v-else-if="!hasResult && !isCalculating" class="section-placeholder">
      填写负载与运动距离（点到点还需运动时间）后自动计算。
    </div>

    <div v-if="isCalculating" class="calc-loading">计算中…</div>
  </div>
</template>

<style scoped>
.result-panel {
  transition: opacity 0.2s ease;
}

.result-panel.is-calculating {
  opacity: 0.55;
}

.calc-message {
  margin-top: 10px;
}

.calc-loading {
  margin-top: 6px;
  font-size: 12px;
  color: var(--ms-text-dim);
}
</style>
