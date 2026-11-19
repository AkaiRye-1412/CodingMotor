<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile, UploadUserFile } from 'element-plus'
import { Upload as UploadIcon, Download, Document, Delete, Back, Box, Loading } from '@element-plus/icons-vue'
import {
  commitImportJob,
  createImportJob,
  deleteImportJob,
  exportImportJob,
  getImportJob,
  listImportJobs,
} from '@/api/import'
import { ApiError } from '@/types/api'
import type { MotorTypeCode } from '@/types/domain'
import type { MotorPrefill } from '@/types/motor'
import type { CommitStatus, ImportCommitItem, ImportCommitResult, ImportJob, ImportJobSummary } from '@/types/import'
import MotorEntryDialog from '@/dialogs/MotorEntryDialog.vue'
import MotorDetailDialog from '@/dialogs/MotorDetailDialog.vue'

/**
 * 样册 PDF → Excel → 入库（06 v2）：上传 → 生成并下载 → 入库。
 *
 * <p>不做草稿与逐条核对：Excel 是唯一数据源，入库按「型号名 + 配置名」覆盖同名电机。
 */
const visible = defineModel<boolean>({ required: true })

type Step = 1 | 2 | 3
const step = ref<Step>(1)
const fileList = ref<UploadUserFile[]>([])
const job = ref<ImportJob | null>(null)
const busy = ref(false)
/** 入库结果（第 3 步的唯一数据源）；为空表示还没入过库。 */
const commitResult = ref<ImportCommitResult | null>(null)
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
  commitResult.value = null
  busy.value = false
  fixVisible.value = false
  fixPrefill.value = null
  fixTarget.value = null
  detailVisible.value = false
  detailMotorId.value = null
}

/**
 * 步骤条可点返回：第 1 步回上传页（清空本次任务）、第 2 步回结果页（保留任务与 Excel）。
 * 用事件委托而非给 el-step 绑 click——不依赖组件内部 attrs 透传，Element Plus 升级也不会失效。
 */
function onStepsClick(event: MouseEvent) {
  if (busy.value) return
  const target = event.target as HTMLElement | null
  const stepEl = target?.closest('.el-step') as HTMLElement | null
  if (!stepEl || !stepEl.parentElement) return
  const index = Array.from(stepEl.parentElement.children).indexOf(stepEl) + 1
  if (index >= step.value) return                  // 只允许往前退，点当前步骤无动作
  if (index === 1) {
    reset()
    return
  }
  step.value = index as Step
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
 * 结果页 / 入库页的「继续上传 PDF」：直接选文件并转换，不必先退回上传页。
 * 与步骤条第 1 步（返回上传页、清空任务）区分开——这个是在原地接着转下一份样册。
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
  step.value = 2              // 立刻离开入库页，避免沿用「正在入库」的提示
  await convertFile(file)
}

/** 上传并转换；成功（含 EMPTY）即切到结果页，失败保留当前结果不动。 */
async function convertFile(file: File) {
  busy.value = true
  try {
    job.value = await createImportJob(file)
    commitResult.value = null            // 新任务 = 之前的入库结果作废
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
    commitResult.value = null
    step.value = 2
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '任务已清理，请重新上传')
  } finally {
    busy.value = false
  }
}

/**
 * 入库（第 3 步）：把 Excel 里的电机写进电机库，主键相同即整体覆盖。
 *
 * <p>点下就切到第 3 步（先给「正在入库」的反馈，百台电机也就一两秒），失败退回第 2 步保留现场。
 */
async function commit() {
  const jobId = job.value?.jobId
  if (!jobId || busy.value) return
  busy.value = true
  commitResult.value = null
  step.value = 3
  try {
    const result = await commitImportJob(jobId)
    commitResult.value = result
    const written = result.created + result.updated
    if (written > 0) {
      ElMessage.success(`已入库 ${written} 台（新增 ${result.created} / 覆盖 ${result.updated}）`)
    } else {
      ElMessage.warning('没有写入任何电机，请查看跳过原因')
    }
  } catch (error) {
    step.value = 2
    ElMessage.error(error instanceof ApiError ? error.message : '入库失败')
  } finally {
    busy.value = false
  }
}

const TYPE_LABEL: Record<string, string> = { linear: '直线', torque: '力矩', coil: '音圈' }

// ---------------------------------------------------------------- 查看参数（已入库项）

const detailVisible = ref(false)
const detailMotorId = ref<number | null>(null)

/** 入库成功（新增 / 覆盖）的电机：打开只读参数页，核对库里到底写进去了什么。 */
function openDetail(row: ImportCommitItem): void {
  if (row.motorId == null) return
  detailMotorId.value = row.motorId
  detailVisible.value = true
}

// ---------------------------------------------------------------- 补全参数（跳过项）

const fixVisible = ref(false)
const fixPrefill = ref<MotorPrefill | null>(null)
const fixTarget = ref<ImportCommitItem | null>(null)

/**
 * 被跳过的电机：直接打开录入表单，样册里已认出来的参数全部预填，用户只补缺的那几项。
 *
 * <p>来源文档传下去，写库时记成「识别草稿」+ 该 PDF，与批量入库口径一致。
 */
function openFix(row: ImportCommitItem): void {
  fixTarget.value = row
  fixPrefill.value = {
    modelName: row.modelName,
    configName: row.configName,
    motorType: row.motorType as MotorTypeCode,
    structure: row.structure,
    cooling: row.cooling,
    spec: row.spec ?? {},
    sourceDoc: commitResult.value?.fileName ?? job.value?.stats.excelName ?? '样册',
    note: `样册识别入库（补全参数，任务 ${commitResult.value?.jobId ?? job.value?.jobId ?? ''}）`,
  }
  fixVisible.value = true
}

/**
 * 补全保存成功后：只把这一行就地改成「已入库」。
 *
 * <p>不重跑整批入库——那会把用户已经手工改过的同名电机又覆盖回去。
 * 想按 Excel 整体重写，回上一步点「入库」即可。
 */
function onFixed(payload: { motorId: number; status: 'CREATED' | 'UPDATED' }): void {
  const result = commitResult.value
  const target = fixTarget.value
  if (!result || !target) return
  const index = result.items.findIndex(
    (item) => item.modelName === target.modelName && item.configName === target.configName,
  )
  if (index < 0) return
  const fixed: ImportCommitItem = {
    ...result.items[index],
    status: payload.status,
    motorId: payload.motorId,
    message:
      payload.status === 'UPDATED'
        ? '补全参数后已整体覆盖电机库同名电机'
        : '补全参数后已新增到电机库',
  }
  result.items.splice(index, 1, fixed)
  if (result.skipped > 0) result.skipped -= 1
  if (payload.status === 'CREATED') result.created += 1
  else result.updated += 1
  fixTarget.value = null
  ElMessage.success(`「${fixed.modelName}」已入库`)
}

function commitLabel(status: CommitStatus): string {
  return ({ CREATED: '新增', UPDATED: '覆盖', SKIPPED: '跳过', FAILED: '失败' } as Record<
    CommitStatus,
    string
  >)[status]
}

function commitTagType(status: CommitStatus): 'success' | 'primary' | 'warning' | 'danger' {
  return ({ CREATED: 'success', UPDATED: 'primary', SKIPPED: 'warning', FAILED: 'danger' } as const)[
    status
  ]
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
    title="样册识别（PDF → Excel → 入库）"
    width="820px"
    append-to-body
    @open="loadHistory"
    @closed="reset"
  >
    <div class="import-steps-wrap" :class="{ 'can-back': step > 1 }" @click="onStepsClick">
      <el-steps :active="step - 1" finish-status="success" class="import-steps">
        <el-step>
          <template #title>
            <!-- 已走过的步骤做成链接样式：← 上传样册 PDF（可点击返回） -->
            <span :class="step > 1 ? 'step-back-title' : ''">
              <el-icon v-if="step > 1"><back /></el-icon>
              <span>上传样册 PDF</span>
            </span>
          </template>
        </el-step>
        <el-step>
          <template #title>
            <span :class="step > 2 ? 'step-back-title' : ''">
              <el-icon v-if="step > 2"><back /></el-icon>
              <span>生成并下载 Excel</span>
            </span>
          </template>
        </el-step>
        <el-step title="入库" />
      </el-steps>
    </div>

    <!-- 结果页 / 入库页共用：选一个新 PDF 直接转换，不必先退回上传页 -->
    <input ref="fileInput" type="file" accept=".pdf" class="hidden-file" @change="onAnotherPicked" />

    <!-- 步骤 1：上传 -->
    <div v-if="step === 1" class="step-body">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="把样册里的参数表转成 Excel，核对后可一键入库"
        description="按表头「符号 / 单位 / 串联 / 并联」定位表格，按坐标还原列与分节；参数表被贴成图片（或整页扫描）时自动走 OCR，识别出的页会在结果里标出，请重点核对。入库只取 K2 模板里的必填参数，缺少必填的电机不会入库。"
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
        <div class="head-actions">
          <el-button :icon="Download" @click="download()">下载 Excel</el-button>
          <el-button type="primary" :icon="Box" :disabled="!isReady" @click="commit">
            入库
          </el-button>
        </div>
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
          继续上传 PDF
        </el-button>
        <el-button type="danger" plain :icon="Delete" @click="removeJob(job.jobId)">
          删除任务
        </el-button>
      </div>
    </div>

    <!-- 步骤 3：入库 -->
    <div v-else-if="step === 3" class="step-body">
      <div v-if="busy" class="commit-loading">
        <el-icon class="is-loading"><loading /></el-icon>
        <span>正在把 Excel 里的电机写进电机库…</span>
      </div>

      <template v-else-if="commitResult">
        <el-alert
          :type="commitResult.created + commitResult.updated > 0 ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="`共 ${commitResult.total} 台：新增 ${commitResult.created} · 覆盖 ${commitResult.updated} · 跳过 ${commitResult.skipped} · 失败 ${commitResult.failed}`"
          description="型号名 + 配置名相同的电机被整体覆盖（不是字段级合并）；「跳过」是缺少必填参数的电机，核对样册补齐后可再点一次「入库」。"
        />

        <el-table :data="commitResult.items" size="small" class="commit-table" max-height="320">
          <el-table-column label="型号" min-width="170">
            <template #default="{ row }">
              <span>{{ row.modelName }}</span>
              <el-tag v-if="row.configName !== '默认'" size="small" effect="plain" class="cfg-tag">
                {{ row.configName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="70">
            <template #default="{ row }">{{ TYPE_LABEL[row.motorType] ?? row.motorType }}</template>
          </el-table-column>
          <el-table-column label="结构 / 冷却" width="130">
            <template #default="{ row }">{{ row.structure }} / {{ row.cooling }}</template>
          </el-table-column>
          <el-table-column label="结果" width="82">
            <template #default="{ row }">
              <el-tag :type="commitTagType(row.status)" size="small">
                {{ commitLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.message }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'SKIPPED' || row.status === 'FAILED'"
                size="small"
                type="warning"
                link
                @click="openFix(row)"
              >
                补全参数
              </el-button>
              <el-button
                v-else-if="row.motorId != null"
                size="small"
                type="primary"
                link
                @click="openDetail(row)"
              >
                查看
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="sheet-note">
          结构与冷却样册一般不给，按模板默认写入（直线 无铁芯/普通、力矩 有铁芯/普通、音圈 音圈/普通），
          可在电机库中按实物修改。入库方式记为「识别草稿」，来源文档即该 PDF。
          <br />
          已入库的电机可点该行右侧「<b>查看</b>」核对库里实际写入的参数（只读，含热耗散常数等仅录入可见的项）。
          被<b>跳过 / 失败</b>的电机可点「补全参数」：样册已识别的值会预填，补齐保存即写入电机库
          （只写这一台，不会重跑整批）；若要按 Excel 整体重写，点上方「生成并下载 Excel」回看结果再点「入库」。
          计算结果页的候选是上一次计算的快照，<b>重新计算一次</b>才会看到新入库的电机。
        </div>

        <div class="step-actions">
          <el-button class="action-back" :icon="UploadIcon" :loading="busy" @click="pickAnother">
            继续上传 PDF
          </el-button>
          <el-button :icon="Download" @click="download()">下载 Excel</el-button>
        </div>
      </template>
    </div>
  </el-dialog>

  <!-- 补全参数：复用电机库录入表单，预填样册已识别的值（不进电机库之前不落任何数据） -->
  <MotorEntryDialog v-model="fixVisible" :prefill="fixPrefill" @saved="onFixed" />
  <!-- 查看参数：已入库的电机，只读展示库里的当前值 -->
  <MotorDetailDialog v-model="detailVisible" :motor-id="detailMotorId" />
</template>

<style scoped>
.import-steps {
  margin-bottom: 16px;
}
/* 已完成的步骤可点击回退：手型 + hover 高亮，暗示可点 */
.can-back :deep(.el-step.is-finish) {
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
.can-back :deep(.el-step:hover .step-back-title) {
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
  color: var(--ms-text-dim);
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
.head-actions {
  display: flex;
  gap: 8px;
}
.commit-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.commit-table {
  margin: 12px 0;
}
.cfg-tag {
  margin-left: 6px;
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
  color: var(--ms-text-dim);
  line-height: 1.7;
}
.muted {
  color: var(--ms-text-dim);
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
