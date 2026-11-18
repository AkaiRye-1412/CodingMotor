<script setup lang="ts">
import { computed } from 'vue'
import { usePlanStore } from '@/stores/plan'
import { useProjectStore } from '@/stores/project'

/** M3：方案翻页 / 新建 / 删除；每个页签独立方案列表。 */
const project = useProjectStore()
const planStore = usePlanStore()

const book = computed(() => planStore.books[project.axisKind])
const currentIndex = computed(() => book.value.currentIndex)
const planCount = computed(() => book.value.plans.length)
const isFirst = computed(() => currentIndex.value === 0)
const isLast = computed(() => currentIndex.value === planCount.value - 1)
</script>

<template>
  <div class="plan-panel">
    <span class="plan-indicator">方案 {{ currentIndex + 1 }} / {{ planCount }}</span>
    <el-button-group size="small">
      <el-button :disabled="isFirst" @click="planStore.previous(project.axisKind)">上一页</el-button>
      <el-button :disabled="isLast" @click="planStore.next(project.axisKind)">下一页</el-button>
    </el-button-group>
    <el-button-group size="small">
      <el-button type="primary" @click="planStore.addPlan(project.axisKind)">新建</el-button>
      <el-button type="danger" plain @click="planStore.removeCurrentPlan(project.axisKind)">
        删除
      </el-button>
    </el-button-group>
  </div>
</template>

<style scoped>
.plan-panel {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.plan-indicator {
  font-size: 12px;
  color: var(--ms-text-secondary);
}

:deep(.el-button) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-button.is-disabled) {
  color: var(--ms-text-dim);
}
</style>
