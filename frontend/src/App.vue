<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import TopBar from '@/views/TopBar.vue'
import PanelSection from '@/components/PanelSection.vue'
import ModePanel from '@/panels/ModePanel.vue'
import PlanPanel from '@/panels/PlanPanel.vue'
import InputPanel from '@/panels/InputPanel.vue'
import MarginPanel from '@/panels/MarginPanel.vue'
import ResultPanel from '@/panels/ResultPanel.vue'
import AppliedPanel from '@/panels/AppliedPanel.vue'
import CandidatePanel from '@/panels/CandidatePanel.vue'
import MotorPanel from '@/panels/MotorPanel.vue'
import CurvePanel from '@/panels/CurvePanel.vue'
import { useMotorStore } from '@/stores/motor'
import { useAutoCalculation } from '@/composables/useAutoCalculation'
import { useCalculation } from '@/composables/useCalculation'
import { ApiError } from '@/types/api'

/**
 * 页面骨架：三列布局按需求 8.2 保持（02 5.2.1）。中列底部只放一个模块：
 * 默认「推荐电机」，选中电机后切到「电机实际应用值」；「选入电机数据」独立成块。
 * 字段模板是 M10 表单与 M8 参数表的必需数据，加载失败时阻塞启动并提供重试。
 */
const motorStore = useMotorStore()
const bootError = ref('')
const rightView = ref<'candidates' | 'applied'>('candidates')

// 选中电机（包括清空）时联动中列底部的推荐/应用值模块；手动切回推荐不会被打断。
watch(
  () => motorStore.selectedMotorId,
  (motorId) => {
    rightView.value = motorId == null ? 'candidates' : 'applied'
  },
)

// 激活 K4 调度：输入变化 → B1 请求（防抖/取消/序号）
useAutoCalculation()

const { result } = useCalculation()

const leftColumnEl = ref<HTMLElement | null>(null)
const middleColumnEl = ref<HTMLElement | null>(null)
const rightColumnEl = ref<HTMLElement | null>(null)
const candidateListMaxHeight = ref<number | null>(null)
const panelContentMinHeight = ref<number | null>(null)
const motorContentMinHeight = ref<number | null>(null)
const motorTableMaxHeight = ref<number | null>(null)
const appliedTableMaxHeight = ref<number | null>(null)
const appliedRowHeight = ref<number | null>(null)
/** 中/右列底部模块的高度：锁到左列固定模块的底边，算出后不再随窗口变化。 */
const middlePanelHeight = ref<number | null>(null)
const rightPanelHeight = ref<number | null>(null)

function lastPanel(column: HTMLElement): HTMLElement | null {
  const panels = column.querySelectorAll<HTMLElement>('.panel')
  return panels.length ? panels[panels.length - 1] : null
}

function panelBodyOf(panel: HTMLElement): HTMLElement | null {
  return panel.querySelector<HTMLElement>('.panel-body')
}

/** 上下内边距：内容区可用高度与「贴底」位置都要扣掉它们。 */
function paddingY(el: HTMLElement): { top: number; bottom: number } {
  const style = getComputedStyle(el)
  return {
    top: Number.parseFloat(style.paddingTop) || 0,
    bottom: Number.parseFloat(style.paddingBottom) || 0,
  }
}

/** 面板上、下边框各 1px：面板高度里要扣掉它们才等于「内容区 + 标题栏 + 内边距」。 */
const PANEL_BORDER = 1

/**
 * 面板内容区高度 = 面板高 − 上下边框 − 标题栏 − 上下内边距。
 *
 * <p>按**目标高度**算，而不是量当前渲染出来的高度：中/右列模块的高度是这次同步刚定的，
 * 此刻 DOM 还是旧高度，量出来会差一轮。
 */
function contentHeightFor(panel: HTMLElement, panelHeight: number): number {
  const panelBody = panelBodyOf(panel)
  if (!panelBody) return 120
  const pad = paddingY(panelBody)
  const head = panel.querySelector<HTMLElement>('.panel-head')
  const headHeight = head ? head.getBoundingClientRect().height : 35
  // 向下取整（不是 round2）：内容区是 min-height，带 0.05 这种小数时，
  // 浏览器把「内边距 + 内容」合成 383.05 再向上进位成 384，
  // 面板内容区就多出 1px 竖滚动条（1600 宽实测）。
  return Math.max(
    120,
    Math.floor(panelHeight - PANEL_BORDER * 2 - headHeight - pad.top - pad.bottom),
  )
}

/**
 * 元素顶边距「内容区顶边」的距离：只由它上方的内容（筛选行、下拉框、判定行）决定，
 * 与面板高度、与列表/表格自身高度都无关，所以不会「量自己撑高自己」。
 */
function topOffset(panel: HTMLElement, el: HTMLElement): number {
  const panelBody = panelBodyOf(panel)
  if (!panelBody) return 0
  return round2(
    el.getBoundingClientRect().top -
      panelBody.getBoundingClientRect().top -
      paddingY(panelBody).top,
  )
}

/** 模块固定高度 → 面板的行内样式（未算出高度时交给 CSS `--ms-module-h` 兜底）。 */
function panelHeightStyle(height: number | null): Record<string, string> | undefined {
  return height == null ? undefined : { height: `${height}px` }
}

/** 元素的外边距（单侧），用于把行间距从可用高度里扣掉。 */
function outerMargin(el: HTMLElement, side: 'top' | 'bottom'): number {
  const style = getComputedStyle(el)
  return Number.parseFloat(side === 'top' ? style.marginTop : style.marginBottom) || 0
}

/** 高度保留两位小数：面板顶/底边常带亚像素（如 784.38），取整会留下 0.4px 的缝。 */
function round2(value: number): number {
  return Math.round(value * 100) / 100
}

/**
 * 应用值表格的逐行高度：把可用高度**全部**均分给 7 行（第 83 轮去掉 48px 上限）。
 *
 * <p>用户的定距口径：选项卡/判定行/表格/结论行之间各自留固定空隙，多出来的高度一律给表格，
 * 不再留在面板底部。下限 28px 跟着表内字号（13px）走，保证再挤也不会压字；再矮就交给表格
 * 按需滚动（不溢出就没有条）。
 */
function appliedRowHeightFor(panel: HTMLElement, tableMax: number | null): number | null {
  if (tableMax == null) return null
  const table = panel.querySelector<HTMLElement>('.applied-table')
  const rows = table?.querySelectorAll('.el-table__body tbody tr').length ?? 0
  if (!table || rows === 0) return null
  const head = table.querySelector<HTMLElement>('thead')
  const measured = head ? head.getBoundingClientRect().height : 0
  // 兜底 32px：Element small 表格表头固定 32，量不到时不能按 0 算（会把行高算大、撑出滚动）
  const headHeight = measured > 0 ? measured : 32
  return Math.max(28, Math.floor((tableMax - headHeight) / rows))
}

/**
 * 三块底部模块（用户输入 / 推荐电机 / 选入电机数据）高度固定，不再随窗口高度伸缩（第 87 轮）。
 *
 * <p>口径：
 * 1) 左列「用户输入」是锚点，高度由 CSS `--ms-module-h` 写死；
 * 2) 中/右列模块的高度 = 锚点底边 − 自身顶边，算出来的也是常量 —— 既保持三列底边齐平，
 *    又不会因为窗口变高而跟着拉大（以前是拿「工作区底边」当基线，所以窗口一动就变）。
 * 3) 剩下的工作只是把固定高度内的空间分给列表/表格：内容最小高度 + 内部滚动区最大高度。
 */
function syncModuleHeights(): void {
  const leftPanel = leftColumnEl.value ? lastPanel(leftColumnEl.value) : null
  // 锚点底边：左列模块高度固定，它上面的面板高度只由内容决定，所以这是常量
  const anchorBottom = leftPanel ? round2(leftPanel.getBoundingClientRect().bottom) : null

  const middleColumn = middleColumnEl.value
  const middlePanel = middleColumn ? lastPanel(middleColumn) : null
  if (middlePanel) {
    const height =
      anchorBottom == null
        ? null
        : Math.max(
            320,
            round2(anchorBottom - middlePanel.getBoundingClientRect().top),
          )
    middlePanelHeight.value = height
    // 首帧还没拿到锚点底边时，按当前渲染高度算，保证内容先撑满
    const targetHeight = height ?? round2(middlePanel.getBoundingClientRect().height)
    const contentHeight = contentHeightFor(middlePanel, targetHeight)
    panelContentMinHeight.value = contentHeight

    const table = middlePanel.querySelector<HTMLElement>('.applied-table')
    if (table) {
      // 表格下方是供电结论方块（.supply-cards，第 88 轮由一行文字改成卡片）
      const supply = middlePanel.querySelector<HTMLElement>('.supply-cards')
      const supplyBox = supply
        ? supply.getBoundingClientRect().height + outerMargin(supply, 'top') + outerMargin(supply, 'bottom')
        : 0
      // 向下取整再多留 1px：给表格的高度带小数时，el-table 会向上取整，
      // 面板内容区就多出 1px 溢出（1440/1600 宽实测 scrollHeight 384 / client 383）。
      // 少 1px 肉眼无感，多 1px 就会冒出滚动条。
      const tableMax = Math.max(
        160,
        Math.floor(contentHeight - topOffset(middlePanel, table) - supplyBox) - 1,
      )
      appliedTableMaxHeight.value = tableMax
      appliedRowHeight.value = appliedRowHeightFor(middlePanel, tableMax)
    } else {
      appliedTableMaxHeight.value = null
      appliedRowHeight.value = null
    }

    const list = middlePanel.querySelector<HTMLElement>('.candidate-list')
    candidateListMaxHeight.value = list
      ? Math.max(120, round2(contentHeight - topOffset(middlePanel, list)))
      : null
  }

  const rightColumn = rightColumnEl.value
  const rightPanel = rightColumn ? lastPanel(rightColumn) : null
  if (!rightPanel) return

  const rightHeight =
    anchorBottom == null
      ? null
      : Math.max(240, round2(anchorBottom - rightPanel.getBoundingClientRect().top))
  rightPanelHeight.value = rightHeight
  const rightContent = contentHeightFor(
    rightPanel,
    rightHeight ?? round2(rightPanel.getBoundingClientRect().height),
  )
  motorContentMinHeight.value = rightContent

  const table = rightPanel.querySelector<HTMLElement>('.motor-param-table')
  motorTableMaxHeight.value = table
    ? Math.max(120, round2(rightContent - topOffset(rightPanel, table)))
    : null
}

let columnObserver: ResizeObserver | null = null
let observedColumns: HTMLElement[] = []

/**
 * 同步是「把面板撑到与左列底部齐平」，只有下限、不会自己缩回去，
 * 所以任何一列的高度变化都得重算一遍 —— 尤其不能只盯左列：
 * 中列上面两个面板（安全余量 / 理论计算结果）晚一步长高时，面板内容的顶边会下移，
 * 旧的 min-height 就永久偏大（第 83 轮实测溢出 12px）。
 */
function observeColumns(): void {
  if (typeof ResizeObserver === 'undefined') return
  const columns = [leftColumnEl.value, middleColumnEl.value, rightColumnEl.value].filter(
    (el): el is HTMLElement => el !== null,
  )
  if (!columns.length) return
  if (columns.length === observedColumns.length && columns.every((el, i) => el === observedColumns[i])) {
    return
  }
  columnObserver ??= new ResizeObserver(scheduleSync)
  columnObserver.disconnect()
  columns.forEach((el) => columnObserver?.observe(el))
  observedColumns = columns
}

/** 合并同一帧内的多次触发，避免观察回调里立刻改高度引发反复重排。 */
let syncQueued = false
function scheduleSync(): void {
  if (syncQueued) return
  syncQueued = true
  requestAnimationFrame(() => {
    syncQueued = false
    syncModuleHeights()
  })
}

onMounted(() => {
  window.addEventListener('resize', scheduleSync)
  observeColumns()
  void bootstrap()
  void nextTick(scheduleSync)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleSync)
  columnObserver?.disconnect()
})

watch(
  () => [motorStore.templatesReady, rightView.value, result.value],
  () => {
    void nextTick(() => {
      observeColumns()
      scheduleSync()
    })
  },
)

async function bootstrap() {
  bootError.value = ''
  try {
    await motorStore.loadTemplates()
  } catch (error) {
    bootError.value =
      error instanceof ApiError ? error.message : '字段模板加载失败，请确认本地服务已启动'
  }
}
</script>

<template>
  <div class="app-shell">
    <TopBar />

    <main v-if="motorStore.templatesReady" class="workspace">
      <div ref="leftColumnEl" class="col col-left">
        <PanelSection title="运动模式">
          <ModePanel />
        </PanelSection>
        <PanelSection title="方案管理">
          <PlanPanel />
        </PanelSection>
        <PanelSection title="用户输入">
          <InputPanel />
        </PanelSection>
      </div>

      <div ref="middleColumnEl" class="col col-middle">
        <PanelSection title="安全余量">
          <MarginPanel />
        </PanelSection>
        <PanelSection title="理论计算结果">
          <ResultPanel />
        </PanelSection>

        <PanelSection :style="panelHeightStyle(middlePanelHeight)">
          <template #head>
            <div class="view-toggle">
              <el-radio-group v-model="rightView" size="small">
                <el-radio-button value="candidates">推荐电机</el-radio-button>
                <el-radio-button value="applied">电机实际应用值</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <CandidatePanel
            v-if="rightView === 'candidates'"
            :list-max-height="candidateListMaxHeight"
            :min-height="panelContentMinHeight"
          />
          <AppliedPanel
            v-else
            :min-height="panelContentMinHeight"
            :table-max-height="appliedTableMaxHeight"
            :row-height="appliedRowHeight"
          />
        </PanelSection>
      </div>

      <div ref="rightColumnEl" class="col col-right">
        <PanelSection title="运动曲线">
          <CurvePanel />
        </PanelSection>

        <PanelSection title="选入电机数据" :style="panelHeightStyle(rightPanelHeight)">
          <MotorPanel
            :min-height="motorContentMinHeight"
            :table-max-height="motorTableMaxHeight"
          />
        </PanelSection>
      </div>
    </main>

    <div v-else class="boot-error">
      <p class="boot-error-text">{{ bootError || '正在加载字段模板…' }}</p>
      <el-button v-if="bootError && !motorStore.templatesLoading" type="primary" @click="bootstrap">
        重试
      </el-button>
    </div>
  </div>
</template>
