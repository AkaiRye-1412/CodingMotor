<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createMotor } from '@/api/motor'
import { useMotorStore } from '@/stores/motor'
import { useProjectStore } from '@/stores/project'
import { ApiError } from '@/types/api'
import type { MotorTypeCode } from '@/types/domain'
import type { FieldSpec, TypeTemplate } from '@/types/template'
import ImportDialog from '@/dialogs/ImportDialog.vue'

/**
 * M10：电机库录入（通道 1 手工录入 / 通道 2 样册识别占位）。
 *
 * <p>对齐冻结原型 manualModal（第 38/50/51 轮）：
 * 必填项组内前置；标签 {cool} 随冷却方式实时替换；类型切换保留已填值并重建模板；
 * 保存校验（标红 / 聚焦首项 / 补填实时刷新 / 按钮禁用）；主键重复拒绝。
 * 音圈 g→kg 等单位换算按模板 factor 在前端完成（I-9），业务校验仍在服务端（C3）。
 */
const visible = defineModel<boolean>({ required: true })

const motorStore = useMotorStore()
const project = useProjectStore()

const importVisible = ref(false)

type Mode = 'channels' | 'manual'
const mode = ref<Mode>('channels')

// ---------------------------------------------------------------- 通道 1 表单状态
const modelName = ref('')
const motorType = ref<MotorTypeCode>('linear')
const configName = ref('')
const structure = ref('')
const cooling = ref('')
const bore = ref('')
/** 字段原始输入（录入单位）；只保留已填的 key，类型切换时天然只带走交集。 */
const values = ref<Record<string, number | null>>({})
const manufacturer = ref('')
const series = ref('')
const sourceDoc = ref('手工录入')
const sourcePage = ref('')
const note = ref('')

/** 是否已点过一次保存（未报错前不打扰用户，报错后补填实时刷新缺项）。 */
const dirty = ref(false)
const saving = ref(false)
const serverError = ref('')

/** 样册用词（第 51 轮）：样册「自冷」= 本产品「普通」冷却。 */
const COOL_LABEL: Record<string, string> = { 普通: '自冷', 水冷: '水冷', 气冷: '气冷' }

const templates = computed(() => motorStore.templates?.templates ?? null)
const template = computed<TypeTemplate | null>(() => templates.value?.[motorType.value] ?? null)

/** 表单分组：只取 display 含 form 的字段；组内必填前置、选填尾随（第 50 轮）。 */
const fieldGroups = computed(() => {
  if (!template.value) return []
  return template.value.groups
    .map((group) => ({
      title: group.title,
      optionalAdd: group.optionalAdd,
      fields: group.fields
        .filter((field) => field.display.includes('form'))
        .slice()
        .sort((a, b) => Number(b.required) - Number(a.required)),
    }))
    .filter((group) => group.fields.length > 0)
})

const coolWord = computed(() => COOL_LABEL[cooling.value] || '自冷')

const missingFields = computed(() =>
  fieldGroups.value.flatMap((group) =>
    group.fields.filter((field) => field.required && values.value[field.key] == null),
  ),
)
const nameMissing = computed(() => modelName.value.trim() === '')
const valid = computed(() => !nameMissing.value && missingFields.value.length === 0)

const validationMessage = computed(() => {
  if (nameMissing.value) return '录入失败：请填写型号名'
  if (missingFields.value.length) {
    const labels = missingFields.value.map((field) => labelOf(field)).join('、')
    return `录入失败：请填写标 * 的必填项（缺 ${missingFields.value.length} 项）：${labels}`
  }
  return ''
})

/** 原型语义：首次保存前按钮可用；报错后随补填实时启停。 */
const saveDisabled = computed(() => (dirty.value && !valid.value) || saving.value)

function labelOf(field: FieldSpec): string {
  return field.label.replace('{cool}', coolWord.value)
}

function isMissing(field: FieldSpec): boolean {
  return dirty.value && field.required && values.value[field.key] == null
}

// ---------------------------------------------------------------- 聚焦定位
interface Focusable {
  focus: () => void
  $el?: HTMLElement
}
const nameInputRef = ref<Focusable | null>(null)
const fieldInputRefs = new Map<string, Focusable>()

function setFieldRef(key: string, el: unknown) {
  if (el) {
    fieldInputRefs.set(key, el as Focusable)
  } else {
    fieldInputRefs.delete(key)
  }
}

async function focusFirstMissing(): Promise<void> {
  await nextTick()
  const target = nameMissing.value
    ? nameInputRef.value
    : fieldInputRefs.get(missingFields.value[0]?.key ?? '')
  if (target) {
    target.focus()
    target.$el?.scrollIntoView({ block: 'center' })
  }
}

// ---------------------------------------------------------------- 模板切换
function applyMetaDefaults(tpl: TypeTemplate): void {
  configName.value = tpl.configs[0] ?? ''
  structure.value = tpl.structures[0] ?? ''
  cooling.value = tpl.coolings[0] ?? ''
  bore.value = tpl.bores[0] ?? ''
}

/**
 * 类型切换：保留已填过的数值（交集字段回填），元信息回到新类型默认项；
 * 换模板后旧类型的缺项提示已失效，一并清掉（原型 1209–1235 行）。
 */
function switchType(next: MotorTypeCode): void {
  if (next === motorType.value || !templates.value) return
  motorType.value = next
  const keep: Record<string, number | null> = {}
  for (const [key, value] of Object.entries(values.value)) {
    if (value != null) keep[key] = value
  }
  values.value = keep
  applyMetaDefaults(templates.value[next])
  dirty.value = false
  serverError.value = ''
}

function openManual(): void {
  // 录入入口两个页签都能开，但从哪个页签打开就默认哪一类，减少误录
  motorType.value = project.axisKind === 'rotary' ? 'torque' : 'linear'
  modelName.value = ''
  values.value = {}
  manufacturer.value = ''
  series.value = ''
  sourceDoc.value = '手工录入'
  sourcePage.value = ''
  note.value = ''
  dirty.value = false
  serverError.value = ''
  if (templates.value) {
    applyMetaDefaults(templates.value[motorType.value])
  }
  mode.value = 'manual'
}

watch(visible, (open) => {
  if (!open) mode.value = 'channels'
})

// ---------------------------------------------------------------- 保存（C3）
async function handleSave(): Promise<void> {
  dirty.value = true
  if (!valid.value) {
    await focusFirstMissing()
    return
  }
  const tpl = template.value
  if (!tpl || !templates.value) return

  saving.value = true
  serverError.value = ''
  try {
    // 按录入单位 → 内部单位写库（音圈线圈质量 g → kg，见字段 factor）
    const spec: Record<string, number> = {}
    for (const group of fieldGroups.value) {
      for (const field of group.fields) {
        const value = values.value[field.key]
        if (value != null) spec[field.key] = value * (field.factor ?? 1)
      }
    }

    const config = configName.value.trim() || '默认'
    await createMotor({
      modelName: modelName.value.trim(),
      configName: config,
      motorType: motorType.value,
      structure: structure.value.trim() || tpl.structures[0] || '',
      cooling: cooling.value.trim() || tpl.coolings[0] || '',
      bore: bore.value.trim() || null,
      manufacturer: manufacturer.value.trim() || null,
      series: series.value.trim() || null,
      sourceDoc: sourceDoc.value.trim() || null,
      sourcePage: sourcePage.value.trim() || null,
      entryMethod: '手工录入',
      enteredOn: new Date().toISOString().slice(0, 10),
      note: note.value.trim() || null,
      spec,
    })
    ElMessage.success(`已入库（${tpl.label} · ${modelName.value.trim()} / ${config}）`)
    visible.value = false
  } catch (error) {
    if (error instanceof ApiError) {
      serverError.value =
        error.code === 'MOTOR_DUPLICATE'
          ? `「${modelName.value.trim()} / ${configName.value.trim() || '默认'}」已存在，请改用其他型号名或配置`
          : formatServerMessage(error)
      // 主键重复 / 服务端校验失败：定位到型号名或第一个缺项
      if (error.code === 'MOTOR_DUPLICATE') {
        await nextTick()
        nameInputRef.value?.focus()
      } else {
        await focusFirstMissing()
      }
    } else {
      serverError.value = '保存失败，请重试'
    }
  } finally {
    saving.value = false
  }
}

function formatServerMessage(error: ApiError): string {
  if (!error.errors.length) return error.message
  const detail = error.errors.map((item) => `${item.field}：${item.reason}`).join('；')
  return `${error.message}——${detail}`
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="mode === 'manual' ? '手工录入电机' : '导入电机参数'"
    :width="mode === 'manual' ? '760px' : '560px'"
    append-to-body
  >
    <!-- 通道选择 -->
    <div v-if="mode === 'channels'" class="import-steps">
      <div class="import-step">
        <h4>通道 1：手工录入（v1）</h4>
        <p>界面内逐条新增，字段与样册口径一致（字段字典唯一）。</p>
        <el-button type="primary" @click="openManual">打开手工录入表单</el-button>
      </div>
      <div class="import-step import-step-later">
        <h4>通道 2：样册转 Excel 并入库（PDF）</h4>
        <p>
          上传样册 PDF → 按坐标解析参数表 → 生成 <b>Excel</b>（参数汇总 / 每型号原版式 / 脚注 / 识别报告）→
          <b>一键入库</b>。入库按「型号名 + 配置名」写库：库里已有就整体覆盖，没有则新增；
          缺少必填参数的电机只跳过、不写库。
        </p>
        <el-button type="primary" @click="importVisible = true">上传样册 PDF</el-button>
      </div>
    </div>

    <!-- 手工录入表单 -->
    <div v-else class="manual-form">
      <div class="entry-row">
        <label class="entry-label" :class="{ 'entry-missing': dirty && nameMissing }">
          型号名<span v-if="dirty && nameMissing" class="req-star">*</span>
        </label>
        <el-input
          ref="nameInputRef"
          v-model="modelName"
          size="small"
          maxlength="100"
          placeholder="如 ADR110-B136"
          :class="{ 'entry-input-missing': dirty && nameMissing }"
        />
        <span class="entry-unit"></span>
      </div>
      <div class="entry-row">
        <label class="entry-label">电机类型</label>
        <el-select
          :model-value="motorType"
          size="small"
          @update:model-value="switchType($event as MotorTypeCode)"
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

      <div v-if="template" class="entry-grid-2">
        <div v-if="template.configs.length" class="entry-row">
          <label class="entry-label">配置</label>
          <el-select v-model="configName" size="small">
            <el-option v-for="item in template.configs" :key="item" :value="item" />
          </el-select>
          <span class="entry-unit"></span>
        </div>
        <div v-if="template.structures.length" class="entry-row">
          <label class="entry-label">结构类型</label>
          <el-select v-model="structure" size="small">
            <el-option v-for="item in template.structures" :key="item" :value="item" />
          </el-select>
          <span class="entry-unit"></span>
        </div>
        <div v-if="template.coolings.length" class="entry-row">
          <label class="entry-label">冷却方式</label>
          <el-select v-model="cooling" size="small">
            <el-option v-for="item in template.coolings" :key="item" :value="item" />
          </el-select>
          <span class="entry-unit"></span>
        </div>
        <div v-if="template.bores.length" class="entry-row">
          <label class="entry-label">中孔规格</label>
          <el-select v-model="bore" size="small">
            <el-option v-for="item in template.bores" :key="item" :value="item" />
          </el-select>
          <span class="entry-unit"></span>
        </div>
      </div>

      <!-- 吸顶错误提示：保存失败后常驻，补填实时刷新 -->
      <div v-if="dirty && !valid" class="entry-error">{{ validationMessage }}</div>
      <div v-else-if="serverError" class="entry-error">{{ serverError }}</div>

      <div class="entry-fields">
        <div v-for="group in fieldGroups" :key="group.title" class="entry-group">
          <div class="entry-group-title" :class="{ 'entry-group-optional': group.optionalAdd }">
            {{ group.title }}
          </div>
          <div
            v-for="field in group.fields"
            :key="field.key"
            class="entry-row"
            :class="{ 'entry-row-req': field.required }"
          >
            <label class="entry-label" :class="{ 'entry-missing': isMissing(field) }">
              {{ labelOf(field) }}<span v-if="field.required" class="req-star">*</span>
            </label>
            <el-input-number
              :ref="(el: unknown) => setFieldRef(field.key, el)"
              :model-value="values[field.key] ?? null"
              size="small"
              :controls="false"
              placeholder="留空"
              class="entry-number"
              :class="{ 'entry-input-missing': isMissing(field) }"
              @update:model-value="values[field.key] = $event"
            />
            <span class="entry-unit">{{ field.unit }}</span>
          </div>
        </div>
      </div>

      <div class="entry-group-title">来源信息</div>
      <div class="entry-grid-2">
        <div class="entry-row">
          <label class="entry-label">厂商</label>
          <el-input v-model="manufacturer" size="small" maxlength="100" placeholder="可选" />
          <span class="entry-unit"></span>
        </div>
        <div class="entry-row">
          <label class="entry-label">系列</label>
          <el-input v-model="series" size="small" maxlength="100" placeholder="可选" />
          <span class="entry-unit"></span>
        </div>
        <div class="entry-row">
          <label class="entry-label">样册文件名</label>
          <el-input v-model="sourceDoc" size="small" maxlength="200" />
          <span class="entry-unit"></span>
        </div>
        <div class="entry-row">
          <label class="entry-label">页码</label>
          <el-input v-model="sourcePage" size="small" maxlength="50" placeholder="可选" />
          <span class="entry-unit"></span>
        </div>
      </div>
      <div class="entry-row">
        <label class="entry-label">说明</label>
        <el-input v-model="note" size="small" maxlength="500" placeholder="选型说明等，可选" />
        <span class="entry-unit"></span>
      </div>
      <div class="entry-footnote">
        带 <span class="req-star">*</span> 为必填，缺项无法入库；其余可留空（留空的项该型号判为「信息不全、无法判定」）。
        录入方式：手工录入 · 录入日期：{{ new Date().toISOString().slice(0, 10) }}
      </div>
    </div>

    <template #footer>
      <template v-if="mode === 'manual'">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saveDisabled" @click="handleSave">
          保存入库
        </el-button>
      </template>
      <el-button v-else type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>

  <ImportDialog v-model="importVisible" />
</template>

<style scoped>
.import-steps {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.import-step {
  border: 1px solid var(--ms-border, #dcdfe6);
  border-radius: 6px;
  padding: 16px;
}

.import-step h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.import-step p {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--ms-text-secondary, #909399);
  line-height: 1.6;
}

.import-step-later {
  opacity: 0.92;
}

.manual-form {
  max-height: 62vh;
  overflow: auto;
}

/* 统一三段行：标签 170px | 输入 1fr | 单位 70px */
.entry-row {
  display: grid;
  grid-template-columns: 170px minmax(0, 1fr) 70px;
  gap: 0 8px;
  align-items: center;
  margin-bottom: 6px;
}

/* 元信息 / 来源区两列并排 */
.entry-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 24px;
}

.entry-label {
  font-size: 13px;
  color: var(--ms-text-primary, #303133);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.entry-missing {
  color: var(--ms-danger, #c45656);
  font-weight: 600;
}

.entry-unit {
  font-size: 12px;
  color: var(--ms-text-secondary, #909399);
}

.entry-number {
  width: 100%;
}

.entry-input-missing :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--ms-danger, #c45656) inset;
}

.entry-error {
  position: sticky;
  top: 0;
  z-index: 5;
  background: var(--ms-danger-bg, #fef0f0);
  color: var(--ms-danger, #c45656);
  border-radius: 4px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 8px;
  white-space: pre-line;
}

.entry-group-title {
  font-size: 13px;
  font-weight: 600;
  margin: 12px 0 6px;
  padding-left: 8px;
  border-left: 3px solid var(--ms-primary, #409eff);
}

.entry-group-optional {
  border-left-color: var(--ms-border, #dcdfe6);
  color: var(--ms-text-secondary, #909399);
}

.entry-footnote {
  margin-top: 12px;
  font-size: 12px;
  color: var(--ms-text-secondary, #909399);
  line-height: 1.6;
}
</style>
