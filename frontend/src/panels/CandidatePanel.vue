<script setup lang="ts">
import { computed } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { useMotorStore } from '@/stores/motor'
import { useProjectStore } from '@/stores/project'
import { useSettingStore } from '@/stores/setting'
import type { MotorTypeCode } from '@/types/domain'
import { formatPercent } from '@/utils/format'

/** M7：候选筛选与推荐。服务端只返回通过准入的型号，按余量升序、不截断。 */
const project = useProjectStore()
const setting = useSettingStore()
const motorStore = useMotorStore()
const { result, isCalculating } = useCalculation()

const axisTypes = computed<MotorTypeCode[]>(() =>
  project.axisKind === 'linear' ? ['linear', 'coil'] : ['torque'],
)

const typeLabels = computed(() => {
  const templates = motorStore.templates
  if (!templates) return [] as string[]
  return templates.types.map((type) => templates.templates[type].label)
})

const structureOptions = computed(() => {
  const templates = motorStore.templates
  if (!templates) return [] as string[]
  const values = new Set<string>()
  axisTypes.value.forEach((type) => {
    templates.templates[type].structures.forEach((value) => values.add(value))
  })
  // 原型第 58 轮：结构过滤不再展示类型名与「超声波力矩电机」
  return [...values].filter(
    (value) => !typeLabels.value.includes(value) && value !== '超声波力矩电机',
  )
})

const coolingOptions = computed(() => {
  const templates = motorStore.templates
  if (!templates) return [] as string[]
  const values = new Set<string>()
  axisTypes.value.forEach((type) => {
    templates.templates[type].coolings.forEach((value) => values.add(value))
  })
  return [...values]
})

const candidates = computed(() => {
  const value = result.value
  return value && value.stage.ok ? (value.candidates ?? []) : []
})

const isActive = (group: 'structure' | 'cooling', value: string): boolean => {
  const target = group === 'structure' ? setting.filterStructures : setting.filterCoolings
  return target.includes(value)
}

const typeLabel = (type: string): string => {
  const templates = motorStore.templates
  if (!templates) return type
  return templates.templates[type as MotorTypeCode]?.label ?? type
}

const modelLabel = (modelName: string, configName: string): string =>
  configName && configName !== '默认' ? `${modelName} / ${configName}` : modelName

function selectCandidate(motorId: number): void {
  motorStore.selectMotor(motorId)
}
</script>

<template>
  <div class="candidate-panel" :class="{ 'is-calculating': isCalculating }">
    <div class="candidate-filters">
      <div class="filter-group">
        <span class="filter-title">结构</span>
        <el-tag
          v-for="value in structureOptions"
          :key="value"
          :type="isActive('structure', value) ? 'primary' : 'info'"
          :effect="isActive('structure', value) ? 'dark' : 'plain'"
          size="small"
          class="filter-chip"
          @click="setting.toggleFilter('structure', value)"
        >
          {{ value }}
        </el-tag>
      </div>
      <div class="filter-group">
        <span class="filter-title">冷却</span>
        <el-tag
          v-for="value in coolingOptions"
          :key="value"
          :type="isActive('cooling', value) ? 'primary' : 'info'"
          :effect="isActive('cooling', value) ? 'dark' : 'plain'"
          size="small"
          class="filter-chip"
          @click="setting.toggleFilter('cooling', value)"
        >
          {{ value }}
        </el-tag>
      </div>
    </div>

    <div v-if="candidates.length === 0" class="empty-state">
      <el-empty description="当前无合规型号" :image-size="52" />
      <p class="empty-tip">可放宽安全余量下限、清除勾选过滤，或先在电机库中补充电机。</p>
    </div>

    <ul v-else class="candidate-list">
      <li
        v-for="candidate in candidates"
        :key="candidate.motorId"
        :class="['candidate-item', { selected: motorStore.selectedMotorId === candidate.motorId }]"
        @click="selectCandidate(candidate.motorId)"
      >
        <el-tooltip
          :content="candidate.reasons.join('；')"
          placement="top"
          :show-after="300"
        >
          <div class="candidate-main">
            <el-tag size="small" type="info" effect="plain">
              {{ typeLabel(candidate.motorType) }}
            </el-tag>
            <span class="candidate-name">
              {{ modelLabel(candidate.modelName, candidate.configName) }}
            </span>
            <el-tag v-if="candidate.overSized" size="small" type="warning" effect="plain">
              余量过大
            </el-tag>
          </div>
        </el-tooltip>
        <span class="candidate-safety">
          Safety: {{ formatPercent(candidate.headroomRatio * 100, 0) }}
        </span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.candidate-panel {
  transition: opacity 0.2s ease;
}

.candidate-panel.is-calculating {
  opacity: 0.55;
}

.candidate-filters {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.filter-title {
  width: 28px;
  font-size: 12px;
  color: var(--ms-text-dim);
}

.filter-chip {
  cursor: pointer;
  user-select: none;
}

.candidate-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.candidate-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 8px;
  border: 1px solid var(--ms-border-soft);
  border-radius: var(--ms-radius);
  cursor: pointer;
}

.candidate-item:hover {
  background: #f5f9ff;
  border-color: var(--ms-accent);
}

.candidate-item.selected {
  background: #ecf5ff;
  border-color: var(--ms-accent);
}

.candidate-main {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.candidate-name {
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.candidate-safety {
  font-size: 12px;
  color: var(--ms-text-secondary);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.empty-state {
  padding-top: 4px;
}

.empty-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ms-text-dim);
  text-align: center;
}
</style>
