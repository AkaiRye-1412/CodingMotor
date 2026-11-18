<script setup lang="ts">
/**
 * M10：电机库录入 / 编辑弹窗。
 * 表单将由 K2 下发的字段模板动态渲染（三类型切换、必填星标、音圈 g→kg 换算）。
 * 脚手架阶段只保留入口与结构，不影响现有 C1–C4 接口。
 */
const visible = defineModel<boolean>({ required: true })
</script>

<template>
  <el-dialog v-model="visible" title="导入电机参数" width="720px" append-to-body>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="录入表单将按字段模板动态渲染（M10，开发中）"
      description="字段顺序、必填标记与单位换算均由 GET /api/motor-templates 下发。"
    />
    <el-empty description="录入表单开发中" :image-size="72" />
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>
