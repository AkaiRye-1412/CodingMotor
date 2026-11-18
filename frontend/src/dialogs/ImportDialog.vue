<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { Upload as UploadIcon } from '@element-plus/icons-vue'
import {
  commitJob,
  createImportJob,
  deleteDraft,
  deleteImportJob,
  editDraft,
  getImportJob,
  listImportJobs,
  recognizePage,
  setDraftChecked,
  validateDraft,
} from '@/api/import'
import { useMotorStore } from '@/stores/motor'
import { ApiError } from '@/types/api'
import type { FieldSpec, TypeTemplate } from '@/types/template'
import type { CommitResult, Draft, DraftIssue, ImportJob, ImportJobSummary } from '@/types/import'
import type { MotorTypeCode } from '@/types/domain'
import type { MotorUpsertRequest } from '@/types/motor'

/** 样册识别三步向导（06 §9）：上传 → 解析核对 → 草稿编辑提交。绝不静默写库（需求 6.4）。 */
const visible = defineModel<boolean>({ required: true })

const motorStore = useMotorStore()

type Step = 1 | 2 | 3
const step = ref<Step>(1)
const pendingFiles = ref<File[]>([])
const job = ref<ImportJob | null>(null)
const busy = ref(false)
const recognizingPageId = ref('')
const editingDraftId = ref('')
const editForm = ref<MotorUpsertRequest | null>(null)
const editValues = ref<Record<string, number | null>>({})
const commitResult = ref<CommitResult | null>(null)
const historyJobs = ref<ImportJobSummary[]>([])

const templates = computed(() => motorStore.templates?.templates ?? null)
const drafts = computed<Draft[]>(() => job.value?.drafts ?? [])
const checkedCount = computed(() => drafts.value.filter((d) => d.checked).length)

function reset() {
  step.value = 1
  pendingFiles.value = []
  job.value = null
  commitResult.value = null
  editingDraftId.value = ''
  editForm.value = null
  editValues.value = {}
}

function onFileChange(_file: UploadFile, files: UploadFile[]) {
  const raws: File[] = []
  for (const f of files) {
    if (f.raw) raws.push(f.raw)
  }
  pendingFiles.value = raws
}

async function loadHistory() {
  try {
    historyJobs.value = await listImportJobs()
  } catch {
    historyJobs.value = []
  }
}

async function createJob() {
  if (pendingFiles.value.length === 0) {
    ElMessage.warning('请先选择 PDF 或图片文件')
    return
  }
  busy.value = true
  try {
    job.value = await createImportJob(pendingFiles.value)
    // 后端上传即自动解析，直接进草稿表格
    step.value = 3
    const draftN = job.value.drafts.length
    const parsedPages = job.value.pages.filter((p) => p.status === 'RECOGNIZED').length
    ElMessage.success(
      `已识别 ${parsedPages}/${job.value.pages.length} 页，生成 ${draftN} 条草稿`,
    )
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建任务失败')
  } finally {
    busy.value = false
  }
}

async function resumeJob(jobId: string) {
  busy.value = true
  try {
    job.value = await getImportJob(jobId)
    step.value = job.value.drafts.length > 0 ? 3 : 2
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载任务失败')
  } finally {
    busy.value = false
  }
}

async function recognize(pageId: string) {
  if (!job.value) return
  recognizingPageId.value = pageId
  try {
    await recognizePage(job.value.jobId, pageId)
    job.value = await getImportJob(job.value.jobId)
    ElMessage.success('已解析')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '解析失败')
  } finally {
    recognizingPageId.value = ''
  }
}

async function toggleCheck(draft: Draft, checked: boolean) {
  if (!job.value) return
  try {
    await setDraftChecked(job.value.jobId, draft.draftId, checked)
    draft.checked = checked
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function runValidate(draft: Draft) {
  if (!job.value) return
  try {
    const issues = await validateDraft(job.value.jobId, draft.draftId)
    draft.issues = issues
    ElMessage[issues.length ? 'warning' : 'success'](
      issues.length ? `存在 ${issues.length} 个问题` : '校验通过',
    )
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '校验失败')
  }
}

async function removeDraft(draft: Draft) {
  if (!job.value) return
  try {
    await ElMessageBox.confirm(`删除草稿「${draft.request.modelName || draft.draftId}」？`, '确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteDraft(job.value.jobId, draft.draftId)
    job.value = await getImportJob(job.value.jobId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

// ----------------------------------------------------------- 草稿编辑（展开行）

function templateOf(draft: Draft): TypeTemplate | null {
  return templates.value?.[draft.request.motorType] ?? null
}

function editFields(draft: Draft): { field: FieldSpec; required: boolean }[] {
  const tpl = templateOf(draft)
  if (!tpl) return []
  return tpl.groups
    .flatMap((g) => g.fields.filter((f) => f.display.includes('form')))
    .map((field) => ({ field, required: field.required }))
    .sort((a, b) => Number(b.required) - Number(a.required))
}

function COOL_WORD(cooling: string): string {
  return cooling === '普通' ? '自冷' : cooling || '自冷'
}

function startEdit(draft: Draft) {
  const tpl = templateOf(draft)
  if (!tpl) {
    ElMessage.warning('未找到字段模板')
    return
  }
  editingDraftId.value = draft.draftId
  editForm.value = { ...draft.request, spec: { ...draft.request.spec } }
  const vals: Record<string, number | null> = {}
  for (const { field } of editFields(draft)) {
    const internal = (draft.request.spec as Record<string, number>)[field.key]
    vals[field.key] = internal != null && Number.isFinite(internal) ? internal / field.factor : null
  }
  editValues.value = vals
}

function cancelEdit() {
  editingDraftId.value = ''
  editForm.value = null
  editValues.value = {}
}

function switchEditType(next: MotorTypeCode) {
  if (!editForm.value) return
  editForm.value.motorType = next
  const tpl = templates.value?.[next]
  if (tpl) {
    editForm.value.structure = tpl.structures[0] ?? ''
    editForm.value.cooling = tpl.coolings[0] ?? ''
    editForm.value.bore = tpl.bores[0] ?? null
  }
}

async function saveEdit() {
  if (!job.value || !editForm.value) return
  const spec: Record<string, number> = {}
  for (const [key, v] of Object.entries(editValues.value)) {
    if (v != null) spec[key] = v
  }
  const tpl = templates.value?.[editForm.value.motorType]
  if (tpl) {
    for (const g of tpl.groups) {
      for (const f of g.fields) {
        if (spec[f.key] != null) spec[f.key] = spec[f.key] * f.factor
      }
    }
  }
  const payload: MotorUpsertRequest = {
    ...editForm.value,
    modelName: editForm.value.modelName.trim(),
    configName: editForm.value.configName.trim() || '默认',
    spec,
  }
  busy.value = true
  try {
    await editDraft(job.value.jobId, editingDraftId.value, payload)
    job.value = await getImportJob(job.value.jobId)
    ElMessage.success('草稿已更新')
    cancelEdit()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    busy.value = false
  }
}

// ----------------------------------------------------------- 提交

async function commit() {
  if (!job.value) return
  if (checkedCount.value === 0) {
    ElMessage.warning('请先勾选要提交的草稿')
    return
  }
  busy.value = true
  try {
    commitResult.value = await commitJob(job.value.jobId)
    job.value = await getImportJob(job.value.jobId)
    const msg = `提交完成：成功 ${commitResult.value.succeeded}，失败 ${commitResult.value.failed}`
    ElMessage[commitResult.value.failed ? 'warning' : 'success'](msg)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '提交失败')
  } finally {
    busy.value = false
  }
}

async function cancelJob() {
  if (!job.value) return
  try {
    await ElMessageBox.confirm('删除该任务及其草稿与上传文件？', '确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteImportJob(job.value.jobId)
    ElMessage.success('任务已删除')
    reset()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

// ----------------------------------------------------------- 模板助手

function fileNameOf(fileId: string): string {
  return job.value?.files.find((f) => f.fileId === fileId)?.name ?? fileId
}
function draftsByPage(pageId: string): Draft[] {
  return drafts.value.filter((d) => d.pageId === pageId)
}
function typeLabel(code: string): string {
  return templates.value?.[code as MotorTypeCode]?.label ?? code
}
function issuesText(issues: DraftIssue[]): string {
  return issues.map((i) => `${i.field}：${i.reason}`).join('\n')
}
function statusLabel(status: string): string {
  return (
    {
      PENDING: '待解析',
      RECOGNIZED: '已解析',
      EMPTY: '无字段',
      OCR_REQUIRED: '需 OCR',
      FAILED: '解析失败',
    } as Record<string, string>
  )[status] ?? status
}
function statusType(status: string): 'info' | 'success' | 'warning' | 'danger' {
  return (
    {
      PENDING: 'info',
      RECOGNIZED: 'success',
      EMPTY: 'warning',
      OCR_REQUIRED: 'warning',
      FAILED: 'danger',
    } as const
  )[status as 'PENDING'] ?? 'info'
}
function confidenceType(c: number): 'success' | 'warning' | 'danger' {
  if (c >= 0.8) return 'success'
  if (c >= 0.6) return 'warning'
  return 'danger'
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="样册识别（草稿确认后入库）"
    width="900px"
    append-to-body
    @open="loadHistory"
    @closed="reset"
  >
    <el-steps :active="step - 1" finish-status="success" class="import-steps">
      <el-step title="上传样册" />
      <el-step title="解析核对" />
      <el-step title="草稿提交" />
    </el-steps>

    <!-- 步骤 1：上传 -->
    <div v-if="step === 1" class="step-body">
      <el-upload
        drag
        multiple
        :auto-upload="false"
        accept=".pdf,.png,.jpg,.jpeg"
        :on-change="onFileChange"
      >
        <el-icon class="el-icon--upload"><upload-icon /></el-icon>
        <div class="el-upload__text">拖拽 PDF / 图片到此，或<em>点击选择</em></div>
        <template #tip>
          <div class="upload-tip">支持 PDF（文本层直接解析）与 PNG/JPG（需 OCR，后续版本）。单文件 ≤ 50MB。</div>
        </template>
      </el-upload>
      <div class="step-actions">
        <el-button type="primary" :loading="busy" :disabled="pendingFiles.length === 0" @click="createJob">
          上传并识别
        </el-button>
      </div>

      <el-collapse v-if="historyJobs.length" class="history-collapse">
        <el-collapse-item title="已有任务" name="1">
          <el-table :data="historyJobs" size="small" :show-header="false">
            <el-table-column>
              <template #default="{ row }">
                <span>{{ row.jobId }}</span>
                <el-tag size="small" style="margin-left: 8px">{{ row.status }}</el-tag>
                <span style="margin-left: 8px; color: #909399">
                  {{ row.fileCount }} 文件 · {{ row.draftCount }} 草稿 · 剩余 {{ row.remainingDays }} 天
                </span>
              </template>
            </el-table-column>
            <el-table-column width="100" align="right">
              <template #default="{ row }">
                <el-button size="small" link @click="resumeJob(row.jobId)">继续</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 步骤 2：页面核对（可重新解析单页；默认上传时已自动识别，无需手动操作） -->
    <div v-else-if="step === 2 && job" class="step-body">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="上传时已自动识别所有页面"
        description="下方可查看每页解析状态，或对某页重新解析。完成后返回草稿表格。"
      />
      <div class="page-list">
        <div v-for="page in job.pages" :key="page.pageId" class="page-card">
          <div class="page-card-head">
            <span class="page-title">{{ fileNameOf(page.fileId) }} · 第 {{ page.pageNumber }} 页</span>
            <el-tag :type="statusType(page.status)" size="small">{{ statusLabel(page.status) }}</el-tag>
          </div>
          <div v-if="page.message" class="page-message">{{ page.message }}</div>
          <div class="page-actions">
            <el-button
              size="small"
              type="primary"
              :loading="recognizingPageId === page.pageId"
              @click="recognize(page.pageId)"
            >
              {{ page.status === 'RECOGNIZED' ? '重新解析' : '解析' }}
            </el-button>
            <span class="page-meta">{{ draftsByPage(page.pageId).length }} 条草稿</span>
          </div>
        </div>
      </div>
      <div class="step-actions">
        <el-button @click="cancelJob">删除任务</el-button>
        <el-button type="primary" @click="step = 3">
          返回草稿表格（{{ drafts.length }} 条）
        </el-button>
      </div>
    </div>

    <!-- 步骤 3：草稿提交 -->
    <div v-else-if="step === 3 && job" class="step-body">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="逐条核对后再勾选提交；绝不静默写库"
        description="置信度 < 0.6 标红提醒（不阻断）；展开行可编辑字段。提交时逐条独立入库（Q4）。"
      />
      <el-table :data="drafts" size="small" row-key="draftId" border style="margin-top: 12px">
        <el-table-column label="勾选" width="56">
          <template #default="{ row }">
            <el-checkbox :model-value="row.checked" @change="(v: boolean) => toggleCheck(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="型号 / 配置" min-width="160">
          <template #default="{ row }">
            <div>{{ row.request.modelName || '(未识别型号)' }}</div>
            <div class="muted">{{ row.request.configName }} · {{ typeLabel(row.request.motorType) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            <el-tag :type="confidenceType(row.confidence)" size="small">
              {{ (row.confidence * 100).toFixed(0) }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90" prop="sourcePage" />
        <el-table-column label="问题" width="70">
          <template #default="{ row }">
            <el-tooltip v-if="row.issues.length" :content="issuesText(row.issues)" placement="top">
              <el-tag type="danger" size="small">{{ row.issues.length }}</el-tag>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" link @click="startEdit(row)">编辑</el-button>
            <el-button size="small" link @click="runValidate(row)">校验</el-button>
            <el-button size="small" link type="danger" @click="removeDraft(row)">删</el-button>
          </template>
        </el-table-column>

        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-if="editingDraftId === row.draftId && editForm" class="draft-edit">
              <div class="entry-grid-2">
                <div class="entry-row">
                  <label class="entry-label">型号名</label>
                  <el-input v-model="editForm.modelName" size="small" />
                  <span class="entry-unit"></span>
                </div>
                <div class="entry-row">
                  <label class="entry-label">电机类型</label>
                  <el-select
                    :model-value="editForm.motorType"
                    size="small"
                    @update:model-value="switchEditType($event as MotorTypeCode)"
                  >
                    <el-option
                      v-for="code in motorStore.templates?.types ?? []"
                      :key="code"
                      :value="code"
                      :label="motorStore.templates?.templates[code].label ?? code"
                    />
                  </el-select>
                  <span class="entry-unit"></span>
                </div>
                <div v-if="templateOf(row)?.configs.length" class="entry-row">
                  <label class="entry-label">配置</label>
                  <el-select v-model="editForm.configName" size="small">
                    <el-option v-for="c in templateOf(row)?.configs ?? []" :key="c" :value="c" />
                  </el-select>
                  <span class="entry-unit"></span>
                </div>
                <div v-if="templateOf(row)?.coolings.length" class="entry-row">
                  <label class="entry-label">冷却方式</label>
                  <el-select v-model="editForm.cooling" size="small">
                    <el-option v-for="c in templateOf(row)?.coolings ?? []" :key="c" :value="c" />
                  </el-select>
                  <span class="entry-unit"></span>
                </div>
              </div>
              <div class="draft-edit-fields">
                <div v-for="{ field, required } in editFields(row)" :key="field.key" class="entry-row">
                  <label class="entry-label">
                    {{ field.label.replace('{cool}', COOL_WORD(editForm.cooling)) }}
                    <span v-if="required" class="req-star">*</span>
                  </label>
                  <el-input-number
                    v-model="editValues[field.key]"
                    size="small"
                    :controls="false"
                    placeholder="留空"
                    class="entry-number"
                  />
                  <span class="entry-unit">{{ field.unit }}</span>
                </div>
              </div>
              <div class="step-actions">
                <el-button size="small" @click="cancelEdit">取消</el-button>
                <el-button size="small" type="primary" :loading="busy" @click="saveEdit">保存</el-button>
              </div>
            </div>
            <div v-else class="draft-raw">
              <div v-for="(rf, key) in row.rawFields" :key="key" class="raw-line">
                <span class="raw-key">{{ key }}</span>
                <span class="raw-value">{{ rf.value }} {{ rf.unit }}</span>
                <span class="raw-text">{{ rf.text }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="commitResult" class="commit-result">
        <el-alert
          :type="commitResult.failed === 0 ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="`成功 ${commitResult.succeeded} / 失败 ${commitResult.failed} / 共 ${commitResult.total}`"
        />
        <el-table :data="commitResult.items" size="small">
          <el-table-column label="草稿" prop="draftId" width="80" />
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '成功' : '失败' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" prop="message" />
        </el-table>
      </div>

      <div class="step-actions">
        <el-button @click="step = 2">页面核对</el-button>
        <el-button @click="cancelJob">删除任务</el-button>
        <el-button type="primary" :loading="busy" :disabled="checkedCount === 0" @click="commit">
          提交勾选（{{ checkedCount }}）
        </el-button>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-steps {
  margin-bottom: 16px;
}
.step-body {
  max-height: 60vh;
  overflow: auto;
}
.step-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.upload-tip {
  color: #909399;
  font-size: 12px;
}
.history-collapse {
  margin-top: 16px;
}
.page-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.page-card {
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 10px 12px;
}
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-title {
  font-weight: 600;
}
.page-message {
  font-size: 12px;
  color: #e6a23c;
  margin-top: 4px;
}
.page-actions {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-meta {
  font-size: 12px;
  color: #909399;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.draft-edit {
  padding: 12px;
  background: #fafafa;
}
.entry-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  column-gap: 24px;
  margin-bottom: 8px;
}
.entry-row {
  display: grid;
  grid-template-columns: 170px 1fr 60px;
  gap: 0 8px;
  align-items: center;
  margin-bottom: 6px;
}
.entry-label {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.entry-number {
  width: 100%;
}
.entry-unit {
  font-size: 12px;
  color: #909399;
}
.draft-edit-fields {
  max-height: 260px;
  overflow: auto;
  margin-bottom: 8px;
}
.draft-raw {
  padding: 8px 12px;
  background: #fafafa;
}
.raw-line {
  font-size: 12px;
  display: flex;
  gap: 12px;
  margin-bottom: 2px;
}
.raw-key {
  width: 100px;
  color: #606266;
}
.raw-value {
  width: 100px;
  color: #409eff;
}
.raw-text {
  color: #909399;
}
.commit-result {
  margin-top: 12px;
}
.req-star {
  color: #c45656;
  font-weight: 700;
}
</style>
