<script setup lang="ts">
import { computed } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { useMotorStore } from '@/stores/motor'
import { formatNumber } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    /** 内容区最小高度：保证模块底部与左列「用户输入」对齐。 */
    minHeight?: number | null
    /** 参数表最大高度：超出时表格内部出现竖向滚动条。 */
    tableMaxHeight?: number | null
  }>(),
  { minHeight: null, tableMaxHeight: null },
)

/** M8：选入电机数据（样册式参数表由服务端按 K2 display 裁剪并附带派生项）。 */
const motorStore = useMotorStore()
const { result } = useCalculation()

const selected = computed(() => (result.value?.stage.ok ? result.value.selectedMotor : null))
const candidates = computed(() => {
  const value = result.value
  return value && value.stage.ok ? (value.candidates ?? []) : []
})

const selectedNotInCandidates = computed(
  () =>
    motorStore.selectedMotorId != null
    && !candidates.value.some((candidate) => candidate.motorId === motorStore.selectedMotorId),
)

const parameterRows = computed(() => {
  const motor = selected.value
  if (!motor) return []
  return motor.parameters.map((parameter) => ({
    key: parameter.key,
    label: parameter.label,
    unit: parameter.unit,
    derived: parameter.derived,
    displayValue: formatNumber(
      parameter.value,
      parameter.key === 'prated' || parameter.key === 'pmax' ? 1 : 4,
    ),
  }))
})

function handleSelect(value: number): void {
  motorStore.selectMotor(value === 0 ? null : value)
}
</script>

<template>
  <div
    class="motor-panel"
    :style="props.minHeight ? { minHeight: `${props.minHeight}px` } : undefined"
  >
    <el-select
      :model-value="motorStore.selectedMotorId ?? 0"
      placeholder="未选取电机"
      size="small"
      class="motor-select"
      @change="handleSelect"
    >
      <el-option :value="0" label="— 未选取 —" />
      <el-option
        v-for="candidate in candidates"
        :key="candidate.motorId"
        :value="candidate.motorId"
        :label="candidate.modelName + (candidate.configName === '默认' ? '' : ' / ' + candidate.configName)"
      />
      <el-option
        v-if="selectedNotInCandidates && motorStore.selectedMotorId != null"
        :value="motorStore.selectedMotorId"
        :label="`电机 #${motorStore.selectedMotorId}（已不在推荐范围）`"
      />
    </el-select>

    <div v-if="selected?.outOfRangeReason" class="out-of-range">
      {{ selected.outOfRangeReason }}
    </div>

    <el-table
      v-if="parameterRows.length"
      :data="parameterRows"
      size="small"
      border
      class="motor-param-table"
      :max-height="props.tableMaxHeight ?? undefined"
      scrollbar-always-on
    >
      <el-table-column prop="label" label="参数">
        <template #default="{ row }">
          <span>{{ row.label }}</span>
          <el-tag v-if="row.derived" size="small" type="warning" effect="plain" class="derived-tag">
            派生
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="displayValue" label="数值" width="96" align="right" />
      <el-table-column
        prop="unit"
        label="单位"
        width="116"
        align="center"
        class-name="motor-unit-cell"
      />
    </el-table>

    <div v-else class="section-placeholder">
      选中电机后在此展示服务端按模板裁剪的参数表（含 Km / 额定功率等派生项）。
    </div>
  </div>
</template>

<style scoped>
.motor-select {
  width: 100%;
  margin-bottom: 8px;
}

.out-of-range {
  margin-bottom: 8px;
  padding: 4px 8px;
  border-radius: 4px;
  background: #fdf4dc;
  color: #8a6100;
  font-size: 12px;
  line-height: 1.6;
}

.derived-tag {
  margin-left: 5px;
}

/* 表格可读性（第 63 轮）：与「电机实际应用值」表同一口径——表头加粗深灰、数值常规深墨 */
:deep(.el-table) {
  color: var(--ms-text);
}

:deep(.el-table th.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-table td.el-table__cell) {
  font-weight: 400;
  color: var(--ms-text);
  font-variant-numeric: tabular-nums;
}

:deep(.motor-unit-cell .cell) {
  white-space: nowrap;
  word-break: keep-all;
}
</style>
