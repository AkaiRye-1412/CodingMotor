<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportReportPdf } from '@/api/report'
import { useMotorStore } from '@/stores/motor'
import { usePlanStore } from '@/stores/plan'
import { useProjectStore } from '@/stores/project'
import { useSettingStore } from '@/stores/setting'
import { ApiError } from '@/types/api'
import type { ReportRequest } from '@/types/report'
import { positiveOrNull } from '@/utils/format'

/**
 * M11：报告导出弹窗。
 * 工程数据为会话级，服务端无法自行获取方案列表，因此由前端完整提交当前页签全部方案。
 */
const visible = defineModel<boolean>({ required: true })

const project = useProjectStore()
const setting = useSettingStore()
const planStore = usePlanStore()
const motorStore = useMotorStore()

const customerName = ref('')
const workingConditionNote = ref('')
const exporting = ref(false)

const currentPlans = computed(() => planStore.books[project.axisKind].plans)
const canExport = computed(() => currentPlans.value.length > 0 && !exporting.value)

function buildRequest(): ReportRequest {
  return {
    projectName: project.projectName.trim(),
    axisName: project.axisName.trim(),
    reportDate: project.projectDate,
    axisKind: project.axisKind,
    customerName: customerName.value.trim() || undefined,
    workingConditionNote: workingConditionNote.value.trim() || undefined,
    settings: {
      mode: setting.mode,
      smooth: { enabled: setting.smoothEnabled, tauMs: setting.smoothTauMs },
      margin: {
        lowerPercent: setting.marginLowerPercent,
        upperPercent: setting.marginUpperPercent,
        upperEnabled: setting.marginUpperEnabled,
      },
    },
    plans: currentPlans.value.map((plan, index) => ({
      planNo: index + 1,
      shape: setting.mode === 'pointToPoint' ? setting.shape : undefined,
      selectedMotorId: motorStore.selectedMotorId,
      plan: {
        load: plan.load ?? 0,
        // 与界面同一口径：报告的「总移动质量/负载惯量」= 负载 + 电机自身质量/惯量（CAL-26）
        motorMass: motorStore.selectedMotorMass,
        distance: plan.distance ?? 0,
        time: positiveOrNull(plan.time),
        dwell: plan.dwell ?? 0,
        maxVelocity: positiveOrNull(plan.maxVelocity),
        maxAcceleration: positiveOrNull(plan.maxAcceleration),
        resistance: plan.resistance ?? 0,
        tiltDeg: plan.tiltDeg ?? 0,
        ambientTemp: plan.ambientTemp ?? 22,
        supplyVoltage:
          plan.supplyVoltage != null && Number.isFinite(plan.supplyVoltage) && plan.supplyVoltage >= 0
            ? plan.supplyVoltage
            : null,
      },
    })),
  }
}

function safeFilename(): string {
  const name = `${project.projectName}-${project.axisName}-电机选型报告.pdf`
  return name.replace(/[\\/:*?"<>|]/g, '_')
}

async function handleExport(): Promise<void> {
  exporting.value = true
  try {
    const blob = await exportReportPdf(buildRequest())
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = safeFilename()
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success('报告已导出')
    visible.value = false
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '报告导出失败，请重试'
    ElMessage.error(message)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="导出报告" width="560px" append-to-body>
    <el-form label-width="80px" label-position="left">
      <el-form-item label="客户名">
        <el-input v-model="customerName" maxlength="100" placeholder="可选" />
      </el-form-item>
      <el-form-item label="工况说明">
        <el-input
          v-model="workingConditionNote"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="可选"
        />
      </el-form-item>
    </el-form>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="`将导出当前页签下全部 ${currentPlans.length} 条方案`"
      description="每个方案按「理论计算结果 · 工况输入 · 运动曲线 · 选入电机数据」成页，只包含选入的电机（不含推荐清单）；无法计算的方案仍会列出并标注原因。"
    />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="exporting" :disabled="!canExport" @click="handleExport">
        导出 PDF
      </el-button>
    </template>
  </el-dialog>
</template>
