<script setup lang="ts">
import { computed } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { useMotorStore } from '@/stores/motor'
import { useProjectStore } from '@/stores/project'
import { COOLING_OPTIONS, useSettingStore } from '@/stores/setting'
import type { MotorTypeCode } from '@/types/domain'
import { formatPercent } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    /** 由 App 按固定面板高度下发：列表可用高度，超出时列表内部滚动；为空时用 CSS 兜底。 */
    listMaxHeight?: number | null
    /** 内容区最小高度：把内容撑满固定高度的面板（与窗口高度无关）。 */
    minHeight?: number | null
  }>(),
  { listMaxHeight: null, minHeight: null },
)

/** M7：候选筛选与推荐。服务端只返回通过准入的型号，按余量升序、不截断。 */
const project = useProjectStore()
const setting = useSettingStore()
const motorStore = useMotorStore()
const { result, isCalculating } = useCalculation()

const isRotary = computed(() => project.axisKind !== 'linear')

// 第 67/68 轮：两个页签都把「结构」筛选替换为「系列」（型号名前缀）。
const rotarySeries = ['ADR', 'ACD', 'AXD', 'ACW', 'ACR', 'AMR', 'AXM', '其他']
const linearSeries = ['ACM', 'AUM', 'ALM', 'AJM', 'AQM', 'AKM', 'AKH', 'AWM', 'AVM', 'AVA', '其他']

const structureOptions = computed(() => (isRotary.value ? rotarySeries : linearSeries))

const candidates = computed(() => {
  const value = result.value
  return value && value.stage.ok ? (value.candidates ?? []) : []
})

const isActive = (value: string): boolean => setting.filterStructures.includes(value)

/**
 * 第 69 轮：「结构」（冷却方式）筛选。
 * 判据在后端 `MotorCooling`：型号结尾 -A/-W 优先，其次库里冷却字段，最后按型号中间段兜底，
 * 所以 `RDM020-A-B2`（-A- 是结构代号）不会被算成气冷。
 */
const isCoolingActive = (value: string): boolean => setting.filterCoolings.includes(value)

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
  <div
    class="candidate-panel"
    :class="{ 'is-calculating': isCalculating }"
    :style="props.minHeight ? { minHeight: `${props.minHeight}px` } : undefined"
  >
    <div class="candidate-filters">
      <div class="filter-group">
        <span class="filter-title">系列</span>
        <el-tag
          v-for="value in structureOptions"
          :key="value"
          :type="isActive(value) ? 'primary' : 'info'"
          :effect="isActive(value) ? 'dark' : 'plain'"
          size="small"
          class="filter-chip"
          @click="setting.toggleFilter(value)"
        >
          {{ value }}
        </el-tag>
      </div>

      <div class="filter-group">
        <span class="filter-title">结构</span>
        <el-tag
          v-for="value in COOLING_OPTIONS"
          :key="value"
          :type="isCoolingActive(value) ? 'primary' : 'info'"
          :effect="isCoolingActive(value) ? 'dark' : 'plain'"
          size="small"
          class="filter-chip"
          @click="setting.toggleCooling(value)"
        >
          {{ value }}
        </el-tag>
      </div>
    </div>

    <div v-if="candidates.length === 0" class="empty-state">
      <el-empty description="当前无合规型号" :image-size="52" />
      <p class="empty-tip">可放宽安全余量下限、清除勾选过滤，或先在电机库中补充电机。</p>
    </div>

    <ul
      v-else
      class="candidate-list"
      :style="props.listMaxHeight ? { maxHeight: `${props.listMaxHeight}px` } : undefined"
    >
      <li
        v-for="candidate in candidates"
        :key="candidate.motorId"
        :class="['candidate-item', { selected: motorStore.selectedMotorId === candidate.motorId }]"
        @click="selectCandidate(candidate.motorId)"
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
        <span class="candidate-safety">
          Safety: {{ formatPercent(candidate.headroomRatio * 100, 0) }}
        </span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
/* 面板高度固定（--ms-module-h）：筛选行占内容高度，列表吃掉剩下的并自己滚 */
.candidate-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  transition: opacity 0.2s ease;
}

.candidate-panel.is-calculating {
  opacity: 0.55;
}

.candidate-filters {
  flex: 0 0 auto;
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
  padding: 0 4px 0 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  /* 兜底高度：JS 会按面板固定高度下发 max-height；这里保证任何情况下都能滚 */
  flex: 1 1 auto;
  min-height: 0;
  max-height: 460px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.candidate-list::-webkit-scrollbar {
  width: 8px;
}

.candidate-list::-webkit-scrollbar-track {
  background: transparent;
}

.candidate-list::-webkit-scrollbar-thumb {
  background: #c0c4cc;
  border-radius: 4px;
}

.candidate-list::-webkit-scrollbar-thumb:hover {
  background: #909399;
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

/* 空态：面板高度固定后垂直居中，避免「暂无型号」挤在顶部、下面空一大片 */
.empty-state {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 0;
  padding-bottom: 16px;
}

.empty-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ms-text-dim);
  text-align: center;
}
</style>
