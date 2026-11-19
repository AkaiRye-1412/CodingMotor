<script setup lang="ts">
import { computed } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { useProjectStore } from '@/stores/project'
import { formatNumber } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    /** 由 App 按左列底部动态计算，内容较短时也把面板撑到与「用户输入」底部齐平。 */
    minHeight?: number | null
  }>(),
  { minHeight: null },
)

/** M8：电机实际应用值 7 项 + 热校核/供电结论（第 45 轮）。 */
const project = useProjectStore()
const { result } = useCalculation()

const selected = computed(() => (result.value?.stage.ok ? result.value.selectedMotor : null))
const applied = computed(() => selected.value?.applied ?? null)

const forceName = computed(() => (project.axisKind === 'linear' ? '力' : '力矩'))
const currentConstantName = computed(() =>
  project.axisKind === 'linear' ? '力常数 Kf' : '转矩常数 Kt',
)

/** 热校核缺的电机参数（后端逐项给出：R25 / Kth / Kf·Kt / Tmax）。 */
const thermalMissing = computed(() => applied.value?.thermalMissing ?? [])

/**
 * 结论行的补充说明：温度能算出来、但缺 tMax 无法判定超温时才显示，
 * 避免与「线圈温度」行里的缺项提示重复。
 */
const thermalHint = computed(() =>
  applied.value?.coilTemp != null && thermalMissing.value.length
    ? `缺 ${thermalMissing.value.join(' / ')}，无法判定是否超温`
    : '',
)

const verdictText = computed(() => {
  switch (applied.value?.thermalVerdict) {
    case 'pass':
      return '电机选型合适'
    case 'fail':
      return '线圈温度超限'
    default:
      return '热校核数据不足'
  }
})

const verdictClass = computed(() => {
  switch (applied.value?.thermalVerdict) {
    case 'pass':
      return 'verdict pass'
    case 'fail':
      return 'verdict fail'
    default:
      return 'verdict unknown'
  }
})

interface AppliedRow {
  label: string
  value: number | null | undefined
  unit: string
  missing: string
}

const rows = computed<AppliedRow[]>(() => {
  const a = applied.value
  const force = forceName.value
  const currentConstant = currentConstantName.value
  return [
    {
      label: '线圈温度',
      value: a?.coilTemp,
      unit: '°C',
      // 提示语以前写死成「缺 热耗散常数 Kth」，实际常常缺的是别的项 → 改成后端逐项列出缺什么
      missing:
        a?.coilTemp == null
          ? `缺 ${(thermalMissing.value.length ? thermalMissing.value : ['热耗散常数 Kth']).join(' / ')}`
          : '',
    },
    {
      label: '连续电流',
      value: a?.contCurrent,
      unit: 'A',
      missing: a?.contCurrent == null ? `缺 ${currentConstant}` : '',
    },
    {
      label: '峰值电流',
      value: a?.peakCurrent,
      unit: 'A',
      missing: a?.peakCurrent == null ? `缺 ${currentConstant}` : '',
    },
    {
      label: '直流母线电压',
      value: a?.busVoltage,
      unit: 'Vdc',
      missing:
        a?.busVoltage == null && a?.missing?.length
          ? `缺 ${a.missing.join(' / ')}`
          : '',
    },
    { label: '安全系数', value: a?.safetyPercent, unit: '%', missing: '' },
    {
      label: `持续${force}负载系数`,
      value: a?.contLoadPercent,
      unit: '%',
      missing: '',
    },
    {
      label: `峰值${force}负载系数`,
      value: a?.peakLoadPercent,
      unit: '%',
      missing: '',
    },
  ]
})
</script>

<template>
  <div
    class="applied-panel"
    :style="props.minHeight ? { minHeight: `${props.minHeight}px` } : undefined"
  >
    <div v-if="!selected" class="section-placeholder">
      选取电机后显示：线圈温度、电流、母线电压、安全系数。
    </div>

    <template v-else>
      <div class="verdict-line">
        <span :class="verdictClass">{{ verdictText }}</span>
        <span v-if="thermalHint" class="muted-note">{{ thermalHint }}</span>
        <span v-if="applied?.overBusVoltage" class="verdict fail">
          需求电压 &gt; 电机 Ubus 耐压
        </span>
      </div>

      <el-table :data="rows" size="small" border class="applied-table">
        <el-table-column prop="label" label="应用值" />
        <el-table-column label="数值" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.value == null && row.missing" class="muted-note">
              —（{{ row.missing }}）
            </span>
            <span v-else>{{ formatNumber(row.value, 1) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="62" align="center" />
      </el-table>

      <div v-if="applied?.supply" :class="['supply-line', applied.supply.pass ? 'ok' : 'bad']">
        供电 {{ formatNumber(applied.supply.have, 1) }} VDC · 所需
        {{ formatNumber(applied.supply.need, 1) }} VDC → {{ applied.supply.text }}
      </div>
      <div
        v-else-if="applied?.busVoltage != null"
        class="supply-line hint"
      >
        填入「供电母线（VDC）」可获得供电是否足够的结论。
      </div>
    </template>
  </div>
</template>

<style scoped>
.verdict-line {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 12px;
}

.verdict {
  font-weight: 700;
}

.verdict.pass {
  color: var(--ms-success);
}

.verdict.fail {
  color: var(--ms-danger);
}

.verdict.unknown {
  color: var(--ms-text-dim);
}

.applied-table {
  width: 100%;
}

:deep(.el-table) {
  color: var(--ms-text-secondary);
  font-weight: 700;
}

:deep(.el-table th.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-table td.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-table) {
  color: var(--ms-text-secondary);
  font-weight: 700;
}

:deep(.el-table th.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-table td.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-table) {
  color: var(--ms-text-secondary);
  font-weight: 700;
}

:deep(.el-table th.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-table td.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

.supply-line {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
}

.supply-line.ok {
  color: var(--ms-success);
}

.supply-line.bad {
  color: var(--ms-danger);
}

.supply-line.hint {
  color: var(--ms-text-dim);
}

.muted-note {
  color: var(--ms-text-dim);
}
</style>
