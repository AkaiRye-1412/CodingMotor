<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile, UploadUserFile } from 'element-plus'
import { Upload as UploadIcon, Download, Document, Delete, Back } from '@element-plus/icons-vue'
import {
  createImportJob,
  deleteImportJob,
  exportImportJob,
  getImportJob,
  listImportJobs,
} from '@/api/import'
import { ApiError } from '@/types/api'
import type { ImportJob, ImportJobSummary } from '@/types/import'

/**
 * 样册 PDF → Excel（06 v2）：上传 → 生成 → 下载。不做草稿、不做入库。
 */
const visible = defineModel<boolean>({ required: true })

type Step = 1 | 2
const step = ref<Step>(1)
const fileList = ref<UploadUserFile[]>([])
const job = ref<ImportJob | null>(null)
const busy = ref(false)
const historyJobs = ref<ImportJobSummary[]>([])
/** 结果页「再次上传」用的隐藏文件选择器（不复用 el-upload，避免两套状态互相干扰）。 */
const fileInput = ref<HTMLInputElement | null>(null)

const picked = computed<File | null>(() => {
  const item = fileList.value[0]
  return item?.raw ?? null
})

const stats = computed(() => job.value?.stats ?? null)
const isReady = computed(() => job.value?.status === 'READY')

/** 参数表以位图嵌入、且自动 OCR 也没能提出数据的页。 */
const imagePages = computed(() => stats.value?.imageTablePages ?? [])
/** 靠 OCR 才拿到数据的页：结果可用，但要人工核对。 */
const ocrPages = computed(() => stats.value?.ocrPages ?? [])
const pdfTypeText = computed(() => {
  if (!stats.value) return ''
  const ocr = ocrPages.value.length > 0
  if (!stats.value.hasTextLayer) return ocr ? '扫描件（已 OCR）' : '扫描件（无文字层）'
  if (ocr) return '文字版（部分页参数表为图片，已 OCR）'
  return imagePages.value.length ? '文字版（参数表为图片）' : '文字版'
})

function reset() {
  step.value = 1
  fileList.value = []
  job.value = null
  busy.value = false
}

/**
 * 结果页点击步骤条第 1 步 = 回到上传页。这是「返回」最自然的位置，不另设按钮：
 * 结果页是终态，返回不撤销任何东西（任务与 Excel 已在服务端生成）。
 * 用事件委托而非给 el-step 绑 click——不依赖组件内部 attrs 透传，Element Plus 升级也不会失效。
 */
function onStepsClick(event: MouseEvent) {
  if (step.value !== 2 || busy.value) return
  const target = event.target as HTMLElement | null
  const stepEl = target?.closest('.el-step')
  if (!stepEl || stepEl.previousElementSibling !== null) return   // 只认第 1 步
  reset()
}

function onExceed(files: File[]) {
  fileList.value = files.slice(-1).map((f) => ({ name: f.name, raw: f } as UploadUserFile))
}

function onRemove() {
  fileList.value = []
}

function onFileChange(file: UploadFile) {
  fileList.value = [{ name: file.name, raw: file.raw } as UploadUserFile]
}

async function loadHistory() {
  try {
    historyJobs.value = await listImportJobs()
  } catch {
    historyJobs.value = []
  }
}

async function convert() {
  const file = picked.value
  if (!file) {
    ElMessage.warning('请先选择样册 PDF')
    return
  }
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    ElMessage.warning('仅支持 PDF 文件')
    return
  }
  await convertFile(file)
}

/**
 * 结果页「再次上传」：直接选文件并转换，不必先退回上传页。
 * 与上方步骤条第 1 步（返回）区分开——那个是「回到上一步」，这个是「在原地看着结果再转一份」。
 */
function pickAnother() {
  if (busy.value) return
  fileInput.value?.click()
}

async function onAnotherPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''            // 清空，保证下次选同一个文件也能触发 change
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    ElMessage.warning('仅支持 PDF 文件')
    return
  }
  await convertFile(file)
}

/** 上传并转换；成功（含 EMPTY）即切到结果页，失败保留当前结果不动。 */
async function convertFile(file: File) {
  busy.value = true
  try {
    job.value = await createImportJob(file)
    step.value = 2
    if (job.value.status === 'READY') {
      ElMessage.success('Excel 已生成，可下载')
    } else {
      ElMessage.warning('未识别到参数表，请查看识别报告')
    }
    loadHistory()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '转换失败')
  } finally {
    busy.value = false
  }
}

async function download(target?: ImportJob | ImportJobSummary) {
  const jobId = target ? target.jobId : job.value?.jobId
  if (!jobId) return
  const name = target && 'stats' in target ? target.stats.excelName : job.value?.stats.excelName
  try {
    await exportImportJob(jobId, name ?? `import-${jobId}.xlsx`)
    ElMessage.success('已开始下载')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '下载失败')
  }
}

async function resume(jobId: string) {
  busy.value = true
  try {
    job.value = await getImportJob(jobId)
    step.value = 2
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '任务已清理，请重新上传')
  } finally {
    busy.value = false
  }
}

async function removeJob(jobId: string) {
  try {
    await ElMessageBox.confirm('删除该任务及其生成的 Excel？', '确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteImportJob(jobId)
    historyJobs.value = historyJobs.value.filter((j) => j.jobId !== jobId)
    if (job.value?.jobId === jobId) reset()
    ElMessage.success('任务已删除')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

function statusLabel(status: string): string {
  return ({ READY: '已生成', EMPTY: '未识别', FAILED: '失败' } as Record<string, string>)[status] ?? status
}

function statusType(status: string): 'success' | 'warning' | 'danger' {
  return ({ READY: 'success', EMPTY: 'warning', FAILED: 'danger' } as const)[
    status as 'READY'
  ] ?? 'warning'
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="样册识别（PDF → Excel）"
    width="820px"
    append-to-body
    @open="loadHistory"
    @closed="reset"
  >
    <div class="import-steps-wrap" :class="{ 'can-back': step === 2 }" @click="onStepsClick">
      <el-steps :active="step - 1" finish-status="success" class="import-steps">
        <el-step>
          <template #title>
            <!-- 结果页把第 1 步做成明确的链接样式：← 上传样册 PDF（可点击返回） -->
            <span v-if="step === 2" class="step-back-title">
              <el-icon><back /></el-icon>
              <span>上传样册 PDF</span>
            </span>
            <span v-else>上传样册 PDF</span>
          </template>
        </el-step>
        <el-step title="生成并下载 Excel" />
      </el-steps>
    </div>

    <!-- 步骤 1：上传 -->
    <div v-if="step === 1" class="step-body">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="只做一件事：把样册里的参数表转成 Excel"
        description="按表头「符号 / 单位 / 串联 / 并联」定位表格，按坐标还原列与分节；参数表被贴成图片（或整页扫描）时自动走 OCR，识别出的页会在结果里标出，请重点核对。"
      />
      <el-upload
        drag
        class="uploader"
        :auto-upload="false"
        :limit="1"
        accept=".pdf"
        :file-list="fileList"
        :on-change="onFileChange"
        :on-remove="onRemove"
        :on-exceed="onExceed"
      >
        <el-icon class="el-icon--upload"><upload-icon /></el-icon>
        <div class="el-upload__text">拖拽样册 PDF 到此，或<em>点击选择</em></div>
        <template #tip>
          <div class="upload-tip">仅支持 PDF（单文件 ≤ 50MB）；扫描件/图片无文字层，需先做 OCR。</div>
        </template>
      </el-upload>
      <div class="step-actions">
        <el-button type="primary" :loading="busy" :disabled="!picked" @click="convert">
          上传并生成 Excel
        </el-button>
      </div>

      <el-collapse v-if="historyJobs.length" class="history-collapse">
        <el-collapse-item :title="`已有任务（${historyJobs.length}）`" name="1">
          <el-table :data="historyJobs" size="small">
            <el-table-column label="文件" prop="fileName" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="页" prop="pageCount" width="60" />
            <el-table-column label="参数表" prop="tableCount" width="80" />
            <el-table-column label="型号" prop="modelCount" width="70" />
            <el-table-column label="剩余" width="80">
              <template #default="{ row }">{{ row.remainingDays }} 天</template>
            </el-table-column>
            <el-table-column label="操作" width="130" align="right">
              <template #default="{ row }">
                <el-button size="small" link @click="resume(row.jobId)">查看</el-button>
                <el-button size="small" link @click="download(row)">下载</el-button>
                <el-button size="small" link type="danger" @click="removeJob(row.jobId)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 步骤 2：结果 -->
    <div v-else-if="step === 2 && job" class="step-body">
      <input
        ref="fileInput"
        type="file"
        accept=".pdf"
        class="hidden-file"
        @change="onAnotherPicked"
      />
      <el-alert
        :type="isReady ? 'success' : 'warning'"
        :closable="false"
        show-icon
        :title="isReady ? job.stats.message : job.stats.message"
      />

      <div class="result-head">
        <div class="result-file">
          <el-icon><document /></el-icon>
          <span>{{ job.files[0]?.name }}</span>
          <el-tag :type="statusType(job.status)" size="small">{{ statusLabel(job.status) }}</el-tag>
        </div>
        <el-button type="primary" :icon="Download" @click="download()">下载 Excel</el-button>
      </div>

      <el-descriptions :column="3" border size="small" class="result-stats">
        <el-descriptions-item label="总页数">{{ stats?.pageCount }}</el-descriptions-item>
        <el-descriptions-item label="识别出参数表的页">{{ stats?.parsedPages }}</el-descriptions-item>
        <el-descriptions-item label="参数表数量">{{ stats?.tableCount }}</el-descriptions-item>
        <el-descriptions-item label="型号数">{{ stats?.modelCount }}</el-descriptions-item>
        <el-descriptions-item label="参数行数">{{ stats?.parameterRows }}</el-descriptions-item>
        <el-descriptions-item label="Excel 数据行">{{ stats?.dataRows }}</el-descriptions-item>
        <el-descriptions-item label="脚注条数">{{ stats?.footnoteCount }}</el-descriptions-item>
        <el-descriptions-item label="PDF 类型">
          {{ pdfTypeText }}
        </el-descriptions-item>
        <el-descriptions-item label="未识别页">
          <span v-if="stats?.skippedPages.length">{{ stats.skippedPages.join('、') }}</span>
          <span v-else class="muted">无</span>
        </el-descriptions-item>
        <el-descriptions-item label="OCR 识别页">
          <span v-if="ocrPages.length" class="ocr-text">{{ ocrPages.join('、') }}</span>
          <span v-else class="muted">无</span>
        </el-descriptions-item>
        <el-descriptions-item label="参数表为图片的页">
          <span v-if="imagePages.length" class="warn-text">{{ imagePages.join('、') }}</span>
          <span v-else class="muted">无</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="ocrPages.length" class="ocr-note">
        第 {{ ocrPages.join('、') }} 页的参数表是位图，数据由 OCR 从图片中识别后解析。
        字号与个别字符（0/O、1/l、°/o）可能误判，导入前请重点核对这几页。
      </div>

      <div class="sheet-note">
        Excel 含 <b>参数汇总</b>（长表/宽表）、<b>每型号原版式页</b>、<b>脚注说明</b> 与 <b>识别报告</b>。
        数值按原样册小数位显示；生成后请与原样册抽查核对。
      </div>

      <div class="step-actions">
        <el-button class="action-back" :icon="UploadIcon" :loading="busy" @click="pickAnother">
          再次上传 PDF
        </el-button>
        <el-button type="danger" plain :icon="Delete" @click="removeJob(job.jobId)">
          删除任务
        </el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.import-steps {
  margin-bottom: 16px;
}
/* 结果页时第 1 步可点击回退到上传页：手型 + hover 高亮，暗示可点 */
.can-back :deep(.el-step:first-child) {
  cursor: pointer;
}
/* 标题做成链接样式（← 图标 + 主色 + 虚线下划线），比单纯 hover 更容易看出可以返回 */
.step-back-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-primary);
  text-decoration: underline;
  text-decoration-style: dashed;
  text-underline-offset: 4px;
}
.can-back :deep(.el-step:first-child:hover .step-back-title) {
  color: var(--el-color-primary-dark-2);
  text-decoration-style: solid;
}
.step-body {
  max-height: 62vh;
  overflow: auto;
}
.uploader {
  margin-top: 12px;
}
.step-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
/* 返回类操作左置，符合「返回在左、破坏性操作在右」的惯例 */
.action-back {
  margin-right: auto;
}
.hidden-file {
  display: none;
}
.upload-tip {
  color: #909399;
  font-size: 12px;
}
.history-collapse {
  margin-top: 16px;
}
.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 14px 0 10px;
}
.result-file {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}
.result-stats {
  margin-bottom: 12px;
}
.sheet-note {
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
.muted {
  color: #909399;
}
.warn-text {
  color: var(--el-color-warning);
  font-weight: 600;
}
.ocr-text {
  color: var(--el-color-primary);
  font-weight: 600;
}
.ocr-note {
  margin-bottom: 12px;
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary);
  border-radius: 4px;
}
</style>
