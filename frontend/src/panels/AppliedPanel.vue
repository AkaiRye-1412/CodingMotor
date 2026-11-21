<script setup lang="ts">
import { computed } from 'vue'
import { useCalculation } from '@/composables/useCalculation'
import { useProjectStore } from '@/stores/project'
import { formatNumber } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    /** 内容区最小高度：把内容撑满固定高度的面板（与窗口高度无关）。 */
    minHeight?: number | null
    /** 参数表高度上限：与 rowHeight 配合，正常情况下用不到（行高已按可用空间均分）。 */
    tableMaxHeight?: number | null
    /** 逐行高度：由 App 按「可用高度 / 行数」算出，保证 7 个参数一次展示完、不出滚动条。 */
    rowHeight?: number | null
  }>(),
  { minHeight: null, tableMaxHeight: null, rowHeight: null },
)

/** M8：电机实际应用值 7 项 + 热校核/供电结论（第 45 轮）。 */
const project = useProjectStore()
const { result } = useCalculation()

const selected = computed(() => (result.value?.stage.ok ? result.value.selectedMotor : null))
const applied = computed(() => selected.value?.applied ?? null)

const forceName = computed(() => (project.axisKind === 'linear' ? '力' : '力矩'))
const currentConstantName = computed(() =>
  project.axisKind === 'linear' ? '力常数 Kf' : '转矩常数 Kt',
)

/** 热校核缺的电机参数（后端逐项给出：R25 / Kth / Kf·Kt / Tmax）。 */
const thermalMissing = computed(() => applied.value?.thermalMissing ?? [])

/**
 * 结论行的补充说明：温度能算出来、但缺 tMax 无法判定超温时才显示，
 * 避免与「线圈温度」行里的缺项提示重复。
 */
const thermalHint = computed(() =>
  applied.value?.coilTemp != null && thermalMissing.value.length
    ? `缺 ${thermalMissing.value.join(' / ')}，无法判定是否超温`
    : '',
)

const verdictText = computed(() => {
  switch (applied.value?.thermalVerdict) {
    case 'pass':
      return '电机选型合适'
    case 'fail':
      return '线圈温度超限'
    default:
      return '热校核数据不足'
  }
})

const verdictClass = computed(() => {
  switch (applied.value?.thermalVerdict) {
    case 'pass':
      return 'verdict pass'
    case 'fail':
      return 'verdict fail'
    default:
      return 'verdict unknown'
  }
})

/** 行高以 CSS 变量下发，单元格用 `height` 精确控制，避免字号/DPI 变化把最后一行挤成滚动。 */
const rowStyle = computed<Record<string, string> | undefined>(() =>
  props.rowHeight ? { '--applied-row-h': `${props.rowHeight}px` } : undefined,
)

interface AppliedRow {
  label: string
  value: number | null | undefined
  unit: string
  missing: string
}

const rows = computed<AppliedRow[]>(() => {
  const a = applied.value
  const force = forceName.value
  const currentConstant = currentConstantName.value
  return [
    {
      label: '线圈温度',
      value: a?.coilTemp,
      unit: '°C',
      // 提示语以前写死成「缺 热耗散常数 Kth」，实际常常缺的是别的项 → 改成后端逐项列出缺什么
      missing:
        a?.coilTemp == null
          ? `缺 ${(thermalMissing.value.length ? thermalMissing.value : ['热耗散常数 Kth']).join(' / ')}`
          : '',
    },
    {
      label: '连续电流',
      value: a?.contCurrent,
      unit: 'A',
      missing: a?.contCurrent == null ? `缺 ${currentConstant}` : '',
    },
    {
      label: '峰值电流',
      value: a?.peakCurrent,
      unit: 'A',
      missing: a?.peakCurrent == null ? `缺 ${currentConstant}` : '',
    },
    {
      label: '直流母线电压',
      value: a?.busVoltage,
      unit: 'Vdc',
      missing:
        a?.busVoltage == null && a?.missing?.length
          ? `缺 ${a.missing.join(' / ')}`
          : '',
    },
    { label: '安全余量', value: a?.safetyPercent, unit: '%', missing: '' },
    {
      label: `持续${force}负载系数`,
      value: a?.contLoadPercent,
      unit: '%',
      missing: '',
    },
    {
      label: `峰值${force}负载系数`,
      value: a?.peakLoadPercent,
      unit: '%',
      missing: '',
    },
  ]
})
</script>

<template>
  <div
    class="applied-panel"
    :style="props.minHeight ? { minHeight: `${props.minHeight}px` } : undefined"
  >
    <div v-if="!selected" class="section-placeholder">
      选取电机后显示：线圈温度、电流、母线电压、安全余量。
    </div>

    <template v-else>
      <div class="verdict-line">
        <span :class="verdictClass">{{ verdictText }}</span>
        <span v-if="thermalHint" class="muted-note">{{ thermalHint }}</span>
        <span v-if="applied?.overBusVoltage" class="verdict fail">
          需求电压 &gt; 电机 Ubus 耐压
        </span>
      </div>

      <el-table
        :data="rows"
        size="small"
        border
        class="applied-table"
        :max-height="props.tableMaxHeight ?? undefined"
        :style="rowStyle"
      >
        <el-table-column prop="label" label="应用值" />
        <el-table-column label="数值" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.value == null && row.missing" class="muted-note">
              —（{{ row.missing }}）
            </span>
            <span v-else>{{ formatNumber(row.value, 1) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="62" align="center" />
      </el-table>

      <!--
        供电结论（第 89 轮）：两格卡片内「标签：数值」改成同一行，
        字号各升一档（标签 13px、数值 17px），一行读完不用上下扫。
      -->
      <div v-if="applied?.supply" class="supply-cards">
        <div class="supply-card">
          <span class="supply-label">供电电压：</span>
          <span class="supply-value" :class="applied.supply.pass ? 'ok' : 'bad'">
            {{ formatNumber(applied.supply.have, 1) }}<span class="supply-unit">VDC</span>
          </span>
        </div>
        <div class="supply-card">
          <span class="supply-label">供电余量：</span>
          <span class="supply-value" :class="applied.supply.pass ? 'ok' : 'bad'">
            {{ formatNumber(applied.supply.remain, 1) }}<span class="supply-unit">VDC</span>
          </span>
        </div>
      </div>
      <div v-else-if="applied?.busVoltage != null" class="supply-cards">
        <div class="supply-hint">填入「供电母线（VDC）」可获得供电是否足够的结论。</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.applied-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/*
 * 排布（第 83 轮，按用户口述）：
 *   选项卡 ──留空（面板内边距 12）──「电机选型合适」──空 10 ──表格── 空 10 ──「供电 …」──底部内边距
 * 多余的高度全部给表格行高（App 下发的 --applied-row-h），不再在首末行加 auto 外边距。
 */
.verdict-line {
  display: flex;
  gap: 8px;
  align-items: center;
  /* 6px + 面板内边距 12px = 离选项卡 18px 的一段空（用户口径「选项卡留一段空」） */
  margin: 6px 0 10px;
  /* 13px：比表格正文大一档，作为整块的状态结论要更显眼（第 84 轮） */
  font-size: 13px;
}

.verdict {
  font-weight: 700;
}

.verdict.pass {
  color: var(--ms-success);
}

.verdict.fail {
  color: var(--ms-danger);
}

.verdict.unknown {
  color: var(--ms-text-dim);
}

.applied-table {
  /* 行高已按固定面板高度均分，表格不再参与拉伸 */
  flex: 0 0 auto;
  width: 100%;
}

/*
 * 行高按可用空间均分（第 80 轮）：行高由 App 下发（--applied-row-h），7 个参数
 * 一次展示完，不溢出就没有滚动条；第 83 轮去掉上限后，多余高度全被表格吃掉。
 */
:deep(.applied-table .el-table__body td.el-table__cell) {
  height: var(--applied-row-h, 30px);
  padding: 0;
}

/*
 * 表内字号 12 → 13px（第 85 轮，与判定行同档）：表头一起放大，
 * 单元格 `height` 在表格布局里只是下限，字号变大时行会被撑高，不会压字。
 */
:deep(.applied-table .el-table__cell .cell) {
  font-size: 13px;
}

/*
 * 表格可读性（第 63 轮）：原来是全表 700 粗 + 浅灰，13px 中文挤在一起发“昏”。
 * 改为「表头加粗深灰、数值常规深墨 + 等宽数字」，扫读时列能对齐、字不糊。
 */
:deep(.el-table) {
  color: var(--ms-text);
}

:deep(.el-table th.el-table__cell) {
  font-weight: 700;
  color: var(--ms-text-secondary);
}

:deep(.el-table td.el-table__cell) {
  font-weight: 400;
  color: var(--ms-text);
  font-variant-numeric: tabular-nums;
}

/*
 * 供电结论（第 88 轮卡片化 → 第 89 轮单行 → 第 90 轮按列分界均分）：
 * 整块是一个盒子，外沿与上方表格左右边严格对齐；中间的 1px 竖线**严格压在两列分界上**
 * —— 右半固定 172px（= 「数值」110 + 「单位」62 两列），剩下的宽度全给左半（「应用值」列）。
 * 所以左半与第一列、右半与后两列一一对应，竖线就是表格的列分界线。
 * 文字一律左对齐，行首缩进与表格单元格内边距一致（见 --supply-inset）。
 */
.supply-cards {
  /* 行首缩进 = 表格单元格 padding(8) − 本盒 1px 边框，文字才与列内文字左缘齐平。 */
  --supply-inset: 7px;
  flex: 0 0 auto;
  display: grid;
  /*
   * 右半 171px + 盒子右侧 1px 边框 = 表格「数值(110) + 单位(62)」两列；
   * 左半吃掉剩下的宽度 = 「应用值」列。这样中间的 1px 竖线正好落在两列分界上。
   */
  grid-template-columns: minmax(0, 1fr) 171px;
  margin-top: 10px;
  border: 1px solid var(--ms-border-soft);
  border-radius: var(--ms-radius);
  background: #fafbfc;
  overflow: hidden;
}

/* 左对齐：标签、数值、单位从左边依次排开，行首与上方表格文字齐平。 */
.supply-card {
  min-width: 0;
  display: flex;
  align-items: baseline;
  justify-content: flex-start;
  gap: 2px;
  padding: 7px 10px 7px var(--supply-inset);
  white-space: nowrap;
  overflow: hidden;
}

/* 分隔线 = 表格「应用值 / 数值」两列的分界线。 */
.supply-card + .supply-card {
  border-left: 1px solid var(--ms-border-soft);
}

.supply-label {
  flex: 0 0 auto;
  font-size: 13px;
  font-weight: 700;
  color: var(--ms-text);
}

.supply-value {
  min-width: 0;
  font-size: 17px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
}

.supply-unit {
  font-size: 13px;
  font-weight: 700;
  color: var(--ms-text-secondary);
  margin-left: 3px;
}

.metric-value.ok,
.supply-value.ok {
  color: var(--ms-success);
}

.metric-value.bad,
.supply-value.bad {
  color: var(--ms-danger);
}

.supply-hint {
  grid-column: 1 / -1;
  padding: 7px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ms-text-dim);
}

.muted-note {
  color: var(--ms-text-dim);
}
</style>
