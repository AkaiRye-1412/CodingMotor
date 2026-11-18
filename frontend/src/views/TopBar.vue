<script setup lang="ts">
import { ref } from 'vue'
import { Document, Upload } from '@element-plus/icons-vue'
import { useProjectStore } from '@/stores/project'
import type { AxisKind } from '@/types/domain'
import MotorEntryDialog from '@/dialogs/MotorEntryDialog.vue'
import ReportDialog from '@/dialogs/ReportDialog.vue'
import brandLogo from '@/assets/akribis-logo.png'

/** M1：项目 / 轴信息与两个动作入口。数据会话级，不持久化。 */
const project = useProjectStore()

const tabs: Array<{ kind: AxisKind; label: string }> = [
  { kind: 'linear', label: '直线马达' },
  { kind: 'rotary', label: '旋转马达' },
]

const entryVisible = ref(false)
const reportVisible = ref(false)
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
      <el-button type="primary" :icon="Upload" size="small" @click="entryVisible = true">
        导入电机参数
      </el-button>
      <el-button :icon="Document" size="small" @click="reportVisible = true">导出报告</el-button>
    </div>
  </header>

  <MotorEntryDialog v-model="entryVisible" />
  <ReportDialog v-model="reportVisible" />
</template>
