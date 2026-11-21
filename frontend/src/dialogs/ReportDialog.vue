<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportReportPdf, exportReportToChosenPath } from '@/api/report'
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
 *
 * <p>保存路径（第 91 轮）：走 `/reports/pdf/save`，由本地后端弹系统「另存为」对话框，
 * 用户可以自己选电脑上的任意目录；后端无图形环境时返回 `REPORT_SAVE_UNAVAILABLE`，
 * 这里自动回退到浏览器下载，保证任何环境都能导出。
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
      direction: plan.direction,
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

/** 回退路径：后端弹不出对话框时，仍按原来的浏览器下载交付。 */
async function downloadInBrowser(): Promise<void> {
  const blob = await exportReportPdf(buildRequest())
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = safeFilename()
  anchor.click()
  URL.revokeObjectURL(url)
  ElMessage.success('系统对话框不可用，已改为浏览器下载')
  visible.value = false
}

async function handleExport(): Promise<void> {
  exporting.value = true
  try {
    const result = await exportReportToChosenPath(buildRequest())
    if (!result.saved) {
      // 用户在「另存为」里点了取消：不算失败，保持弹窗让用户可以重来
      ElMessage.info('已取消保存')
      return
    }
    visible.value = false
    await ElMessageBox.alert(`报告已保存到：${result.path}`, '导出完成', {
      confirmButtonText: '知道了',
      type: 'success',
    })
  } catch (error) {
    if (error instanceof ApiError && error.code === 'REPORT_SAVE_UNAVAILABLE') {
      try {
        await downloadInBrowser()
      } catch (fallbackError) {
        const message =
          fallbackError instanceof ApiError ? fallbackError.message : '报告导出失败，请重试'
        ElMessage.error(message)
      }
      return
    }
    const message = error instanceof ApiError ? error.message : '报告导出失败，请重试'
    ElMessage.error(message)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="导出报告" width="580px" append-to-body>
    <el-form label-width="82px" label-position="left">
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

    <div v-if="exporting" class="saving-hint">
      正在等待保存位置：请到系统弹出的「另存为」对话框里选择目录并点「保存」（默认已填好文件名，可直接改路径）。
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="exporting" :disabled="!canExport" @click="handleExport">
        导出 PDF
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 导出时系统对话框可能被浏览器挡在后面，给一句明确指引，避免以为「点了没反应」 */
.saving-hint {
  margin-top: 10px;
  padding: 8px 10px;
  border-left: 3px solid var(--ms-accent);
  background: #f5f9ff;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ms-text-secondary);
}
</style>
