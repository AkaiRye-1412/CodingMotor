<script setup lang="ts">
import { ref } from 'vue'
import { Delete, Document, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useProjectStore } from '@/stores/project'
import { clearAllMotors } from '@/api/motor'
import type { AxisKind } from '@/types/domain'
import MotorEntryDialog from '@/dialogs/MotorEntryDialog.vue'
import ImportDialog from '@/dialogs/ImportDialog.vue'
import ReportDialog from '@/dialogs/ReportDialog.vue'
import brandLogo from '@/assets/akribis-logo.png'

/** M1：项目 / 轴信息与两个动作入口。数据会话级，不持久化。 */
const project = useProjectStore()

const tabs: Array<{ kind: AxisKind; label: string }> = [
  { kind: 'linear', label: '直线电机' },
  { kind: 'rotary', label: '旋转电机' },
]

const entryVisible = ref(false)
const importVisible = ref(false)
const reportVisible = ref(false)
const clearing = ref(false)

/**
 * 「导入电机参数」弹窗里的通道 2 交给顶层打开样册弹窗：
 * 这样两个弹窗互不嵌套（补全参数时还要从样册弹窗里反向打开录入表单，嵌套会绕成循环引用）。
 */
function openImport(): void {
  entryVisible.value = false
  importVisible.value = true
}

/** 清空数据库：二次确认后才执行，成功后刷新页面回到干净状态。 */
async function confirmClearAll() {
  try {
    await ElMessageBox.confirm(
      '此操作将删除电机库中的全部电机数据，且无法恢复。确定继续吗？',
      '清空数据库',
      {
        confirmButtonText: '确认清空',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }

  clearing.value = true
  try {
    await clearAllMotors()
    ElMessage.success('数据库已清空')
    window.location.reload()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '清空失败，请重试')
  } finally {
    clearing.value = false
  }
}
</script>

<template>
  <header class="topbar">
    <img class="brand-logo" :src="brandLogo" alt="Akribis" />

    <div class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab.kind"
        type="button"
        class="tab"
        :class="{ active: project.axisKind === tab.kind }"
        @click="project.switchAxisKind(tab.kind)"
      >
        {{ tab.label }}
      </button>
    </div>

    <div class="topbar-meta">
      <label class="meta-field">
        <span>机器名称</span>
        <el-input v-model="project.projectName" size="small" maxlength="100" />
      </label>
      <label class="meta-field">
        <span>轴名称</span>
        <el-input v-model="project.axisName" size="small" maxlength="100" />
      </label>
      <label class="meta-field">
        <span>日期</span>
        <el-date-picker
          v-model="project.projectDate"
          type="date"
          size="small"
          value-format="YYYY-MM-DD"
          :clearable="false"
        />
      </label>
    </div>

    <div class="topbar-actions">
      <el-button
        type="danger"
        plain
        :icon="Delete"
        size="small"
        :loading="clearing"
        @click="confirmClearAll"
      >
        清空数据库
      </el-button>
      <el-button type="primary" :icon="Upload" size="small" @click="entryVisible = true">
        导入电机参数
      </el-button>
      <el-button :icon="Document" size="small" @click="reportVisible = true">导出报告</el-button>
    </div>
  </header>

  <MotorEntryDialog v-model="entryVisible" @open-import="openImport" />
  <ImportDialog v-model="importVisible" />
  <ReportDialog v-model="reportVisible" />
</template>
