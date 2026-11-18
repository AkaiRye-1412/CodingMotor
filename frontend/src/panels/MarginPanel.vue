<script setup lang="ts">
import { useSettingStore } from '@/stores/setting'

/** M5：安全余量门槛。下限为过滤器，上限为软警告（第 52 轮）。 */
const setting = useSettingStore()
</script>

<template>
  <div class="margin-panel">
    <div class="margin-row">
      <span class="margin-label">下限</span>
      <el-slider
        v-model="setting.marginLowerPercent"
        :min="0"
        :max="100"
        :step="5"
        size="small"
        class="margin-slider"
      />
      <span class="margin-value">{{ setting.marginLowerPercent }}%</span>
    </div>

    <div class="margin-row">
      <span class="margin-label">上限</span>
      <el-slider
        v-model="setting.marginUpperPercent"
        :min="100"
        :max="500"
        :step="25"
        size="small"
        class="margin-slider"
        :disabled="!setting.marginUpperEnabled"
      />
      <span class="margin-value">{{ setting.marginUpperPercent }}%</span>
    </div>

    <div class="margin-row">
      <el-checkbox v-model="setting.marginUpperEnabled">加上限判定（余量过大仅提示）</el-checkbox>
    </div>
  </div>
</template>

<style scoped>
.margin-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.margin-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.margin-label {
  width: 28px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ms-text-secondary);
}

.margin-slider {
  flex: 1;
}

.margin-value {
  width: 42px;
  text-align: right;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
</style>
