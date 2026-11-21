<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { getMotor } from '@/api/motor'
import { useMotorStore } from '@/stores/motor'
import { ApiError } from '@/types/api'
import type { MotorDetail } from '@/types/motor'
import type { FieldSpec } from '@/types/template'
import MotorEntryDialog from '@/dialogs/MotorEntryDialog.vue'

/**
 * 查看一台电机的全部参数（电机库里的**当前值**），并可当场改。
 *
 * <p>入口：样册入库结果里点「查看」——刚入库成功也要能核对「样册识别出来的值到底写进去了什么」，
 * 不必再切到电机库翻这台电机。数据走 C2 详情接口，所以看到的就是库里真实值（不是入库时的快照）。
 *
 * <p>2026-11-21（用户要求）：这里以前是纯只读，「查看」完想改一个识别错的参数就没路可走。
 * 现在页脚多了「编辑参数」——复用 M10 的录入表单（`MotorEntryDialog` + `editMotorId`）改，
 * 保存走 C4 整体替换，保存后本页自动重读，看到的就是改完的当前值。
 *
 * <p>展示口径：按 K2 模板分组**全字段**列出（含仅录入表单可见的 kth / pn / tmax 等，
 * 因为这些正是样册会识别、用户最想核对的项）；库里没有值的显示「—」，必填而缺值的再标「缺」。
 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ motorId?: number | null }>()
const emit = defineEmits<{ (e: 'updated', payload: { motorId: number }): void }>()

const motorStore = useMotorStore()
const detail = ref<MotorDetail | null>(null)
const loading = ref(false)
const error = ref('')

// ---------------------------------------------------------------- 编辑（C4）
const editVisible = ref(false)
const editMotorId = ref<number | null>(null)

function openEdit(): void {
  const id = detail.value?.motorId
  if (id == null) return
  editMotorId.value = id
  editVisible.value = true
}

/** 保存成功：重读本页（改完的值就是新的「当前值」），并通知外部（入库结果页）刷新。 */
function onEdited(payload: { motorId: number }): void {
  load(true)
  emit('updated', payload)
}

/** 样册用词：产品「普通」冷却 = 样册「自冷」（第 51 轮）。 */
const COOL_LABEL: Record<string, string> = { 普通: '自冷', 水冷: '水冷', 气冷: '气冷' }

const template = computed(() => {
  const type = detail.value?.motorType
  return type ? motorStore.templates?.templates[type] ?? null : null
})

const coolWord = computed(() => COOL_LABEL[detail.value?.cooling ?? ''] ?? detail.value?.cooling ?? '')

const title = computed(() =>
  detail.value ? `${detail.value.modelName} 参数` : '电机参数',
)

interface ParamRow {
  key: string
  label: string
  unit: string
  text: string
  /** 库里没有值。 */
  empty: boolean
  /** 必填但库里没值：计算里该型号会被判为「信息不全」。 */
  missing: boolean
}

/**
 * 数值格式化：库里存的是**内部单位**，模板 unit 即显示单位（2026-11-20 CAL-25 起两者完全一致，
 * 音圈质量也是 kg，factor 恒为 1；保留 factor 除法只为兼容将来可能出现的量纲差异）。
 *
 * <p>入库是 BigDecimal，样册小数位已保留；这里只压掉浮点尾差（0.30000000000000004 → 0.3）。
 */
function formatNumber(value: number): string {
  if (!Number.isFinite(value)) return '—'
  if (Number.isInteger(value)) return String(value)
  return String(Number(value.toPrecision(10)))
}

function toRow(field: FieldSpec): ParamRow {
  const raw = detail.value?.spec?.[field.key]
  const label = field.label.replace('{cool}', coolWord.value)
  if (raw == null || raw === '') {
    return { key: field.key, label, unit: field.unit, text: '—', empty: true, missing: field.required }
  }
  if (typeof raw === 'string' && !Number.isFinite(Number(raw))) {
    return { key: field.key, label, unit: field.unit, text: raw, empty: false, missing: false }
  }
  const num = Number(raw) / (field.factor ?? 1)
  return { key: field.key, label, unit: field.unit, text: formatNumber(num), empty: false, missing: false }
}

/** 按模板分组展开（不过滤 display：kth / pn / tmax 等 form-only 字段也要能核对）。 */
const groups = computed(() => {
  const tpl = template.value
  if (!tpl) return []
  return tpl.groups
    .map((group) => ({ title: group.title, rows: group.fields.map(toRow) }))
    .filter((group) => group.rows.length > 0)
})

const filledCount = computed(() => groups.value.reduce((n, g) => n + g.rows.filter((r) => !r.empty).length, 0))
const missingRows = computed(() => groups.value.flatMap((g) => g.rows.filter((r) => r.missing)))

/**
 * 读库里当前值。
 *
 * @param silent 静默刷新（改完参数后重读）：保留旧内容不闪空，失败也不把已显示的内容换成错误页
 */
async function load(silent = false): Promise<void> {
  const id = props.motorId
  if (id == null) return
  if (!silent) {
    loading.value = true
    error.value = ''
    detail.value = null
  }
  try {
    detail.value = await getMotor(id)
    error.value = ''
  } catch (err) {
    if (silent) return
    error.value = err instanceof ApiError ? err.message : '读取电机参数失败，请重试'
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    load()
  } else {
    editVisible.value = false
  }
})
watch(
  () => props.motorId,
  () => {
    if (visible.value) load()
  },
)
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="800px" append-to-body>
    <div v-if="loading" class="detail-loading">
      <el-icon class="is-loading"><loading /></el-icon>
      <span>正在读取电机参数…</span>
    </div>

    <el-alert v-else-if="error" type="error" :closable="false" show-icon :title="error" />

    <div v-else-if="detail" class="detail-body">
      <el-descriptions :column="3" border size="small" class="detail-meta">
        <el-descriptions-item label="型号名">{{ detail.modelName }}</el-descriptions-item>
        <el-descriptions-item label="配置">{{ detail.configName }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ template?.label ?? detail.motorType }}</el-descriptions-item>
        <el-descriptions-item label="结构">{{ detail.structure || '—' }}</el-descriptions-item>
        <el-descriptions-item label="冷却">{{ coolWord || '—' }}</el-descriptions-item>
        <el-descriptions-item label="中孔">{{ detail.bore || '—' }}</el-descriptions-item>
        <el-descriptions-item label="厂商">{{ detail.manufacturer || '—' }}</el-descriptions-item>
        <el-descriptions-item label="系列">{{ detail.series || '—' }}</el-descriptions-item>
        <el-descriptions-item label="录入方式">{{ detail.entryMethod }}</el-descriptions-item>
        <el-descriptions-item label="来源文档">{{ detail.sourceDoc || '—' }}</el-descriptions-item>
        <el-descriptions-item label="页码">{{ detail.sourcePage || '—' }}</el-descriptions-item>
        <el-descriptions-item label="录入日期">{{ detail.enteredOn || '—' }}</el-descriptions-item>
      </el-descriptions>

      <div v-for="group in groups" :key="group.title" class="param-group">
        <div class="param-group-title">{{ group.title }}</div>
        <div class="param-grid">
          <div
            v-for="row in group.rows"
            :key="row.key"
            class="param-cell"
            :class="{ 'param-cell-empty': row.empty, 'param-cell-missing': row.missing }"
          >
            <span class="param-label" :title="row.label">
              {{ row.label }}
              <em v-if="row.missing" class="param-missing-tag">缺</em>
            </span>
            <span class="param-value">{{ row.text }}</span>
            <span class="param-unit">{{ row.unit }}</span>
          </div>
        </div>
      </div>

      <div v-if="detail.note" class="detail-note">说明：{{ detail.note }}</div>

      <div class="detail-footnote">
        共 {{ filledCount }} 项有值。这是电机库里的<b>当前值</b>（不是入库时的快照），单位已按录入口径换算；
        「—」表示该型号这项没有值<template v-if="missingRows.length">，
        标「缺」的 {{ missingRows.length }} 项是必填但库里为空，这类型号在计算里会判为「信息不全」</template>。
        识别错了的项不必重新识别样册：点下方「<b>编辑参数</b>」直接改，保存即写回电机库（同一台，不会新增）。
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :disabled="!detail || loading" @click="openEdit">编辑参数</el-button>
    </template>
  </el-dialog>

  <!-- 编辑：复用 M10 录入表单（C4 整体替换），保存后本页重读 -->
  <MotorEntryDialog v-model="editVisible" :edit-motor-id="editMotorId" @saved="onEdited" />
</template>

<style scoped>
.detail-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 28px 4px;
  color: var(--ms-text-secondary);
  font-size: 13px;
}

.detail-body {
  max-height: 64vh;
  overflow: auto;
  padding-right: 4px;
}

.detail-meta {
  margin-bottom: 12px;
}

.param-group {
  margin-bottom: 10px;
}

.param-group-title {
  font-size: 13px;
  font-weight: 600;
  margin: 10px 0 6px;
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
}

/* 三列：标签自适应 | 值右对齐 | 单位不定宽（`Vpeak/(m/s)` 这类长单位不能换行） */
.param-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 20px;
}

.param-cell {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 0 8px;
  align-items: baseline;
  padding: 3px 0;
  border-bottom: 1px dashed var(--ms-border-light, #ebeef5);
  font-size: 13px;
}

.param-label {
  color: var(--ms-text);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.param-value {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--ms-text);
  text-align: right;
}

.param-unit {
  font-size: 12px;
  color: var(--ms-text-secondary);
  /* 长单位（Vpeak/(m/s)、N/SqRt(W)）宁可挤标签也不能折行：折行会把整行撑高 */
  white-space: nowrap;
  justify-self: end;
  min-width: 52px;
}

.param-cell-empty .param-value {
  font-weight: 400;
  color: var(--ms-text-placeholder, #c0c4cc);
}

.param-cell-missing .param-value {
  color: var(--ms-danger, #c45656);
}

.param-missing-tag {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  font-size: 11px;
  font-style: normal;
  line-height: 15px;
  color: var(--ms-danger, #c45656);
  background: var(--ms-danger-bg, #fef0f0);
  border-radius: 3px;
}

.detail-note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ms-text-secondary);
  line-height: 1.7;
}

.detail-footnote {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--ms-border-light, #ebeef5);
  font-size: 12px;
  color: var(--ms-text-secondary);
  line-height: 1.7;
}
</style>
