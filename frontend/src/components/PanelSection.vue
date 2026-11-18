<script setup lang="ts">
/**
 * 面板容器：统一标题栏与内容区，保持原型「三列 + 分组面板」的信息架构（5.2.1）。
 * 标题区通过 #head 插槽允许放置页签切换等控件。
 */
const props = withDefaults(
  defineProps<{
    title?: string
    hint?: string
  }>(),
  { title: '', hint: '' },
)
</script>

<template>
  <section class="panel">
    <header class="panel-head">
      <slot name="head">
        <span class="panel-title">{{ props.title }}</span>
      </slot>
      <span v-if="props.hint" class="panel-hint">{{ props.hint }}</span>
    </header>
    <div class="panel-body">
      <slot />
    </div>
  </section>
</template>
