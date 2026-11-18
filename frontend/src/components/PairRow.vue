<script setup lang="ts">
/**
 * M4 双列输入行：左列用户输入，右列实际参与计算的值。
 * 标记：必填星标 / 推算标签；选填不再单独标注（用户要求移除「可选」徽标）。
 */
withDefaults(
  defineProps<{
    label: string
    unit?: string
    badge?: 'required' | 'optional' | 'derived'
    hint?: string
  }>(),
  { unit: '', badge: undefined, hint: '' },
)
</script>

<template>
  <div class="pair-row">
    <label class="pair-label">
      {{ label }}
      <span v-if="badge === 'required'" class="req-star">*</span>
      <el-tag v-else-if="badge === 'derived'" size="small" type="warning" effect="plain">推算</el-tag>
    </label>
    <div class="pair-input">
      <slot name="input" />
    </div>
    <div class="pair-result">
      <slot name="result" />
    </div>
    <span class="pair-unit">{{ unit }}</span>
  </div>
  <div v-if="hint" class="pair-hint">{{ hint }}</div>
</template>
