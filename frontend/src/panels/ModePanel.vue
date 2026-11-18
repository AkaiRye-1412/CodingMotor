<script setup lang="ts">
import { useSettingStore } from '@/stores/setting'

/** M2：运动模式 / 形状 / S 曲线。仅点到点可选梯形或三角形（第 26 轮）。 */
const setting = useSettingStore()
</script>

<template>
  <div class="mode-panel">
    <el-radio-group v-model="setting.mode" size="small">
      <el-radio-button value="pointToPoint">点到点</el-radio-button>
      <el-radio-button value="custom">自定义</el-radio-button>
    </el-radio-group>

    <div class="mode-row">
      <span class="mode-label">形状</span>
      <el-radio-group v-model="setting.shape" size="small" :disabled="setting.mode === 'custom'">
        <el-radio value="trapezoid">梯形</el-radio>
        <el-radio value="triangle">三角形</el-radio>
      </el-radio-group>
    </div>

    <div class="mode-row">
      <el-checkbox v-model="setting.smoothEnabled">S 曲线</el-checkbox>
      <el-input-number
        v-model="setting.smoothTauMs"
        :min="1"
        :max="1000"
        :step="1"
        :disabled="!setting.smoothEnabled"
        size="small"
        controls-position="right"
      />
      <span class="mode-label">ms</span>
    </div>
  </div>
</template>

<style scoped>
.mode-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mode-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mode-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--ms-text);
}

:deep(.el-radio__label) {
  font-weight: 700;
  color: var(--ms-text);
}
</style>
