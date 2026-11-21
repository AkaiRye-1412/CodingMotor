<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { createMotor, getMotor, listMotors, updateMotor } from '@/api/motor'
import { useMotorStore } from '@/stores/motor'
import { useProjectStore } from '@/stores/project'
import { ApiError } from '@/types/api'
import type { EntryMethod, MotorTypeCode } from '@/types/domain'
import type { MotorDetail, MotorPrefill, MotorUpsertRequest } from '@/types/motor'
import type { FieldSpec, TypeTemplate } from '@/types/template'

/**
 * M10：电机库录入（通道 1 手工录入 / 通道 2 样册识别）。
 *
 * <p>对齐冻结原型 manualModal（第 38/50/51 轮）：
 * 必填项组内前置；标签 {cool} 随冷却方式实时替换；类型切换保留已填值并重建模板；
 * 保存校验（标红 / 聚焦首项 / 补填实时刷新 / 按钮禁用）；主键重复拒绝。
 * 单位与内部单位一致（CAL-25：音圈质量也按 kg 录入），factor 机制保留但恒为 1；业务校验仍在服务端（C3）。
 *
 * <p>2026-11-19 增补：支持 `prefill`——样册入库结果里被跳过的电机，点「补全参数」直接在这里
 * 打开同一张表单，参数已按样册预填，补齐保存即写入电机库（来源记为「识别草稿」）。
 *
 * <p>2026-11-21 增补：支持 `editMotorId`——已入库的电机（样册入库结果里的「查看」→「编辑参数」）
 * 用同一张表单改参数，保存走 C4（PUT），而不是再新增一条。三条与 C4 对齐的口径：
 * ①类型不可改（改类型等于换规格表，服务端 MOTOR_TYPE_MISMATCH）；②中孔、录入方式、录入日期
 * 沿用库里的值（表单里不提供这几项，不能因为「没给」就清空）；③「录入必填」项（直线/音圈的
 * 线圈质量、力矩的转动惯量）**原本就缺**的放过——不逼用户为了改别的字段补历史数据。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  prefill?: MotorPrefill | null
  /** 非空 = 编辑模式：按 id 读库里的当前值回填，保存走 C4 整体替换。 */
  editMotorId?: number | null
}>()
const emit = defineEmits<{
  (e: 'open-import'): void
  (e: 'saved', payload: { motorId: number; status: 'CREATED' | 'UPDATED' }): void
}>()

const motorStore = useMotorStore()
const project = useProjectStore()

/** 是否由样册入库的「补全参数」打开：决定标题、来源信息与写库的 entryMethod。 */
const fromImport = computed(() => !!props.prefill)
/** 是否编辑库里已有的记录（C4）。 */
const editing = computed(() => props.editMotorId != null)

type Mode = 'channels' | 'manual'
const mode = ref<Mode>('channels')

// ---------------------------------------------------------------- 通道 1 表单状态
const modelName = ref('')
const motorType = ref<MotorTypeCode>('linear')
const configName = ref('')
/** 结构类型：表单不再让用户选（样册也不给），恒按模板首项写入——DB 非空 + 模板枚举校验，必须带值提交。 */
const structure = ref('')
const cooling = ref('')
/** 中孔：表单不提供（可空列），编辑时原样带回，避免「没给」被当成「清空」。 */
const bore = ref<string | null>(null)
/** 字段原始输入（录入单位）；只保留已填的 key，类型切换时天然只带走交集。 */
const values = ref<Record<string, number | null>>({})
const manufacturer = ref('')
const series = ref('')
const sourceDoc = ref('手工录入')
const sourcePage = ref('')
const note = ref('')

/** 编辑模式：库里原有的录入方式 / 录入日期（不在表单里，保存时原样写回）。 */
const loadedEntryMethod = ref<EntryMethod>('手工录入')
const loadedEnteredOn = ref<string | null>(null)
/** 编辑模式：打开时库里已有值的字段（判断「录入必填」是否属于「原本就缺」）。 */
const originalKeys = ref<Set<string>>(new Set())
/** 编辑模式：正在读库里当前值。 */
const loadingDetail = ref(false)

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

/** 「录入必填」但不受「准入必填」管的两项（与 domain entryRequiredKeys 一致）。 */
const entryRequiredKeys = computed(() =>
  motorType.value === 'torque' ? ['inertia'] : ['moverMass'],
)

/**
 * 必填而未填。
 *
 * <p>编辑老记录时，「录入必填」而库里本来就没有的项放过（与 C4 校验同一口径，见 MotorSpecValidator）——
 * 否则用户只想改个冷却方式，也会被「缺线圈质量」挡住。
 */
function requiredMissing(field: FieldSpec): boolean {
  if (!field.required || values.value[field.key] != null) return false
  return !(
    editing.value
    && entryRequiredKeys.value.includes(field.key)
    && !originalKeys.value.has(field.key)
  )
}

const missingFields = computed(() =>
  fieldGroups.value.flatMap((group) => group.fields.filter(requiredMissing)),
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
  return dirty.value && requiredMissing(field)
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
  bore.value = null
  originalKeys.value = new Set()
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

/**
 * 规格（后端内部单位）→ 表单值（录入单位）。
 *
 * <p>只取模板里有的字段：详情接口的 spec 还带 `derived` 这类服务端派生项，塞进表单会被当成未知字段。
 * 后端给的是**内部单位**（K2 factor 已乘过），表单显示录入单位，所以这里要除回去。
 */
function toFormValues(
  type: MotorTypeCode,
  spec: Record<string, number | string | null>,
): Record<string, number | null> {
  const filled: Record<string, number | null> = {}
  const tpl = templates.value?.[type] ?? null
  if (!tpl) return filled
  for (const group of tpl.groups) {
    for (const field of group.fields) {
      const raw = spec[field.key]
      if (raw == null) continue
      const num = typeof raw === 'number' ? raw : Number(raw)
      if (!Number.isFinite(num)) continue
      const factor = field.factor ?? 1
      filled[field.key] = factor === 1 ? num : num / factor
    }
  }
  return filled
}

/**
 * 用样册入库结果预填表单。
 *
 * <p>样册没认出来的字段保持空，正是用户要补的那几项。
 */
function applyPrefill(prefill: MotorPrefill): void {
  motorType.value = prefill.motorType
  modelName.value = prefill.modelName
  configName.value = prefill.configName || '默认'
  structure.value = prefill.structure || ''
  cooling.value = prefill.cooling || ''
  bore.value = null
  manufacturer.value = ''
  series.value = ''
  sourceDoc.value = prefill.sourceDoc || '样册'
  sourcePage.value = ''
  note.value = prefill.note || ''
  values.value = toFormValues(prefill.motorType, prefill.spec)
  originalKeys.value = new Set()
  dirty.value = false
  serverError.value = ''
  mode.value = 'manual'
}

/**
 * 编辑模式：把库里的**当前值**填进表单（全字段，含仅录入表单可见的 kth / tmax 等）。
 *
 * <p>录入方式、录入日期、中孔原样带回不显示在表单里——保存时原样写回，免得「表单没有」被当成「清空」。
 */
function applyDetail(detail: MotorDetail): void {
  motorType.value = detail.motorType
  modelName.value = detail.modelName
  configName.value = detail.configName
  structure.value = detail.structure
  cooling.value = detail.cooling
  bore.value = detail.bore ?? null
  manufacturer.value = detail.manufacturer ?? ''
  series.value = detail.series ?? ''
  sourceDoc.value = detail.sourceDoc ?? ''
  sourcePage.value = detail.sourcePage ?? ''
  note.value = detail.note ?? ''
  loadedEntryMethod.value = detail.entryMethod
  loadedEnteredOn.value = detail.enteredOn
  values.value = toFormValues(detail.motorType, detail.spec)
  originalKeys.value = new Set(Object.keys(values.value))
  dirty.value = false
  serverError.value = ''
  mode.value = 'manual'
}

/** 编辑模式打开：先读库（C2），数据回来再填，避免闪一下空表单。 */
let editLoadSeq = 0
async function loadForEdit(id: number): Promise<void> {
  const seq = ++editLoadSeq
  mode.value = 'manual'
  loadingDetail.value = true
  serverError.value = ''
  try {
    const detail = await getMotor(id)
    if (seq !== editLoadSeq) return          // 连点两台时只留最后一次
    applyDetail(detail)
  } catch (error) {
    if (seq !== editLoadSeq) return
    serverError.value = error instanceof ApiError ? error.message : '读取电机参数失败，请重试'
  } finally {
    if (seq === editLoadSeq) loadingDetail.value = false
  }
}

const filledCount = computed(() => Object.keys(values.value).length)

/** 弹窗标题：编辑 / 样册补全 / 手工录入三态要能一眼分开。 */
const dialogTitle = computed(() => {
  if (mode.value !== 'manual') return '导入电机参数'
  if (editing.value) return `编辑电机参数${modelName.value ? `：${modelName.value}` : ''}`
  return fromImport.value ? '补全参数并入库' : '手工录入电机'
})

watch(visible, (open) => {
  if (!open) {
    editLoadSeq += 1      // 关窗即作废在途的读取，避免数据回来后覆盖下次打开的表单
    mode.value = 'channels'
    return
  }
  if (editing.value && props.editMotorId != null) {
    loadForEdit(props.editMotorId)
    return
  }
  if (props.prefill) {
    applyPrefill(props.prefill)
  } else {
    mode.value = 'channels'
  }
})

// 同一个弹窗被复用时（先补全 A 再补全 B / 先看 A 再改 B），入参变了要重新填
watch(
  () => props.prefill,
  (prefill) => {
    if (visible.value && prefill) applyPrefill(prefill)
  },
)
watch(
  () => props.editMotorId,
  (id) => {
    if (visible.value && id != null) loadForEdit(id)
  },
)

// ---------------------------------------------------------------- 保存（C3）

/** 补全时若撞上同名主键，用它找回那条记录改成覆盖（避免用户被「重名」卡住）。 */
async function findExistingId(name: string, config: string): Promise<number | null> {
  try {
    const page = await listMotors({ keyword: name, size: 100 })
    const hit = page.items.find((item) => item.modelName === name && item.configName === config)
    return hit ? hit.motorId : null
  } catch {
    return null
  }
}

async function handleSave(): Promise<void> {
  dirty.value = true
  if (!valid.value) {
    await focusFirstMissing()
    return
  }
  const tpl = template.value
  if (!tpl || !templates.value) return

  // 按录入单位 → 内部单位写库；CAL-25 起模板 unit 就是内部单位、factor 恒为 1（音圈质量也填 kg）
  const spec: Record<string, number> = {}
  for (const group of fieldGroups.value) {
    for (const field of group.fields) {
      const value = values.value[field.key]
      if (value != null) spec[field.key] = value * (field.factor ?? 1)
    }
  }

  const config = configName.value.trim() || '默认'
  const fromSample = fromImport.value
  const today = new Date().toISOString().slice(0, 10)
  const request: MotorUpsertRequest = {
    modelName: modelName.value.trim(),
    configName: config,
    motorType: motorType.value,
    structure: structure.value.trim() || tpl.structures[0] || '',
    cooling: cooling.value.trim() || tpl.coolings[0] || '',
    // 中孔规格不在表单里选：新建不编造默认值（力矩的 标准/特殊 需要时在电机库中补）；
    // 编辑时沿用库里的值——「表单没有这一项」不能当成「要清空」（C4 是整体替换）。
    bore: editing.value ? bore.value : null,
    manufacturer: manufacturer.value.trim() || null,
    series: series.value.trim() || null,
    // 样册补全：来源文档就是那份 PDF，录入方式与批量入库一致（识别草稿）
    sourceDoc: fromSample ? (props.prefill?.sourceDoc ?? '样册') : sourceDoc.value.trim() || null,
    sourcePage: fromSample ? null : sourcePage.value.trim() || null,
    // 编辑已有记录：录入方式与录入日期属于「历史」，不在表单里，原样写回
    entryMethod: fromSample ? '识别草稿' : editing.value ? loadedEntryMethod.value : '手工录入',
    enteredOn: editing.value ? (loadedEnteredOn.value ?? today) : today,
    note: fromSample ? (props.prefill?.note ?? null) : note.value.trim() || null,
    spec,
  }

  // 编辑：直接 C4 整体替换这台电机（不新增记录，主键仍是「型号名 + 配置名」）
  if (editing.value && props.editMotorId != null) {
    saving.value = true
    serverError.value = ''
    try {
      await updateMotor(props.editMotorId, request)
      ElMessage.success(`已保存修改（${tpl.label} · ${request.modelName} / ${config}）`)
      emit('saved', { motorId: props.editMotorId, status: 'UPDATED' })
      visible.value = false
    } catch (error) {
      if (error instanceof ApiError) {
        serverError.value =
          error.code === 'MOTOR_DUPLICATE'
            ? `「${request.modelName} / ${config}」已有另一台电机，请改用其他型号名或配置`
            : formatServerMessage(error)
        if (error.code === 'MOTOR_DUPLICATE') {
          await nextTick()
          nameInputRef.value?.focus()
        }
      } else {
        serverError.value = '保存失败，请重试'
      }
    } finally {
      saving.value = false
    }
    return
  }

  saving.value = true
  serverError.value = ''
  try {
    const created = await createMotor(request)
    ElMessage.success(`已入库（${tpl.label} · ${request.modelName} / ${config}）`)
    emit('saved', { motorId: created.motorId, status: 'CREATED' })
    visible.value = false
  } catch (error) {
    if (fromSample && error instanceof ApiError && error.code === 'MOTOR_DUPLICATE') {
      const existingId = await findExistingId(request.modelName, request.configName)
      if (existingId != null) {
        try {
          await updateMotor(existingId, request)
          ElMessage.success(`已覆盖电机库里的「${request.modelName} / ${config}」`)
          emit('saved', { motorId: existingId, status: 'UPDATED' })
          visible.value = false
          return
        } catch (updateError) {
          serverError.value =
            updateError instanceof ApiError ? formatServerMessage(updateError) : '保存失败，请重试'
          return
        }
      }
    }
    if (error instanceof ApiError) {
      serverError.value =
        error.code === 'MOTOR_DUPLICATE'
          ? `「${request.modelName} / ${config}」已存在，请改用其他型号名或配置`
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
    :title="dialogTitle"
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
        <el-button type="primary" @click="emit('open-import')">上传样册 PDF</el-button>
      </div>
    </div>

    <!-- 手工录入表单 -->
    <div v-else class="manual-form">
      <div v-if="loadingDetail" class="entry-loading">
        <el-icon class="is-loading"><loading /></el-icon>
        <span>正在读取电机参数…</span>
      </div>

      <template v-else>
      <!-- 编辑：说清「改的是哪台、存到哪去」，以及类型为什么不能改 -->
      <div v-if="editing" class="prefill-tip">
        正在修改电机库里的这台记录，参数已按库里的<b>当前值</b>填好 {{ filledCount }} 项；改完点「保存修改」即整体替换，
        库里的其他方案不用重建（仍是同一条电机）。<br />
        型号名 + 配置名是主键：改了它等于把这条记录改成另一台电机的名字；电机类型不可修改（改类型等于换规格表）。
      </div>
      <!-- 样册补全：说清「哪些是预填的、空的就是缺的」，避免用户以为要重填一遍 -->
      <div v-else-if="fromImport" class="prefill-tip">
        {{ prefill?.modelName }} 的参数已按样册预填 <b>{{ filledCount }}</b> 项；带
        <span class="req-star">*</span> 的空项就是样册里没识别到的，补齐后保存即写入电机库
        （录入方式记为「识别草稿」）。
      </div>
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
        <!-- 编辑时锁死：改类型等于换规格表，服务端 C4 会直接拒（MOTOR_TYPE_MISMATCH） -->
        <el-select
          :model-value="motorType"
          size="small"
          :disabled="editing"
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
        <div v-if="template.coolings.length" class="entry-row">
          <label class="entry-label">冷却方式</label>
          <el-select v-model="cooling" size="small">
            <el-option v-for="item in template.coolings" :key="item" :value="item" />
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
        <template v-if="editing">
          结构类型不在表单里改（沿用本机现有的「{{ structure || '—' }}」，可让样册重新入库覆盖）；
          中孔规格沿用原值。录入方式：{{ loadedEntryMethod }} · 录入日期：{{ loadedEnteredOn || '—' }}
        </template>
        <template v-else>
          结构类型不在表单里选，按模板默认写入（直线 无铁芯 / 力矩 有铁芯 / 音圈 音圈）；中孔规格也不在表单里填
          （留空，需要时在电机库中补）。录入方式：手工录入 · 录入日期：{{ new Date().toISOString().slice(0, 10) }}
        </template>
      </div>
      </template>
    </div>

    <template #footer>
      <template v-if="mode === 'manual'">
        <el-button @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="saveDisabled || loadingDetail"
          @click="handleSave"
        >
          {{ editing ? '保存修改' : fromImport ? '保存并入库' : '保存入库' }}
        </el-button>
      </template>
      <el-button v-else type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
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
  color: var(--ms-text-secondary);
  line-height: 1.6;
}

.import-step-later {
  opacity: 0.92;
}

.manual-form {
  max-height: 62vh;
  overflow: auto;
}

/* 编辑模式读库里当前值时的占位（时长通常 <100ms，仅避免闪空表单） */
.entry-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 28px 4px;
  color: var(--ms-text-secondary);
  font-size: 13px;
}

/* 统一三段行：标签 170px | 输入 1fr | 单位 80px（放得下 Vpeak/(m/s)、N/SqRt(W) 这类长单位） */
.entry-row {
  display: grid;
  grid-template-columns: 170px minmax(0, 1fr) 80px;
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
  color: var(--ms-text);
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
  color: var(--ms-text-secondary);
  /* 长单位折行会把整行撑高、输入框错位，宁可溢出也保持一行 */
  white-space: nowrap;
}

.entry-number {
  width: 100%;
}

.entry-input-missing :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--ms-danger, #c45656) inset;
}

/* 样册补全说明条：与错误提示区分（信息色，不抢红） */
.prefill-tip {
  margin-bottom: 10px;
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary);
  border-radius: 4px;
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
  color: var(--ms-text-secondary);
}

.entry-footnote {
  margin-top: 12px;
  font-size: 12px;
  color: var(--ms-text-secondary);
  line-height: 1.6;
}
</style>
