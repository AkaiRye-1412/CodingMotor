<script setup lang="ts">
import { computed } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { useMotorStore } from '@/stores/motor'
import { useProjectStore } from '@/stores/project'
import { useSettingStore } from '@/stores/setting'
import type { MotorTypeCode } from '@/types/domain'
import { formatPercent } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    /** 由 App 按左列底部动态计算，保证模块底部对齐；为空时用 CSS 兜底高度。 */
    listMaxHeight?: number | null
    /** 内容区最小高度，候选较少时也把面板撑到与「用户输入」底部齐平。 */
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
  padding: 0 4px 0 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
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
