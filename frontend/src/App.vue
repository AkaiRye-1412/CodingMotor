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

function lastPanel(column: HTMLElement): HTMLElement | null {
  const panels = column.querySelectorAll<HTMLElement>('.panel')
  return panels.length ? panels[panels.length - 1] : null
}

/** 以左列底部为基准，反推某个面板内容区需要的最小高度。 */
function contentMinHeight(panel: HTMLElement, leftBottom: number): number {
  const panelBody = panel.querySelector<HTMLElement>('.panel-body')
  if (!panelBody) return 120
  const style = getComputedStyle(panelBody)
  const paddingTop = Number.parseFloat(style.paddingTop) || 0
  const paddingBottom = Number.parseFloat(style.paddingBottom) || 0
  const bodyRect = panelBody.getBoundingClientRect()
  const panelBottom = panel.getBoundingClientRect().bottom
  return Math.max(
    120,
    Math.ceil(bodyRect.height - paddingTop - paddingBottom + leftBottom - panelBottom),
  )
}

/**
 * 左列「用户输入」的底部是整页对齐基线：
 * 1) 中列推荐/应用值模块：内容最小高度 + 候选列表最大高度（出现滚动条）；
 * 2) 右列选入电机数据模块：内容最小高度 + 参数表最大高度（出现滚动条）。
 */
function syncModuleHeights(): void {
  const leftColumn = leftColumnEl.value
  const middleColumn = middleColumnEl.value
  if (!leftColumn || !middleColumn) return

  const leftBottom = leftColumn.getBoundingClientRect().bottom

  const middlePanel = lastPanel(middleColumn)
  if (middlePanel) {
    panelContentMinHeight.value = contentMinHeight(middlePanel, leftBottom)

    const panelBody = middlePanel.querySelector<HTMLElement>('.panel-body')
    const list = panelBody?.querySelector<HTMLElement>('.candidate-list')
    if (panelBody && list) {
      const paddingBottom = Number.parseFloat(getComputedStyle(panelBody).paddingBottom) || 0
      candidateListMaxHeight.value = Math.max(
        160,
        Math.floor(leftBottom - list.getBoundingClientRect().top - paddingBottom),
      )
    }
  }

  const rightColumn = rightColumnEl.value
  const rightPanel = rightColumn ? lastPanel(rightColumn) : null
  if (!rightPanel) return

  motorContentMinHeight.value = contentMinHeight(rightPanel, leftBottom)

  const panelBody = rightPanel.querySelector<HTMLElement>('.panel-body')
  const table = panelBody?.querySelector<HTMLElement>('.motor-param-table')
  if (panelBody && table) {
    const paddingBottom = Number.parseFloat(getComputedStyle(panelBody).paddingBottom) || 0
    motorTableMaxHeight.value = Math.max(
      120,
      Math.floor(leftBottom - table.getBoundingClientRect().top - paddingBottom),
    )
  }
}

let leftColumnObserver: ResizeObserver | null = null
let observedLeftColumn: HTMLElement | null = null

function observeLeftColumn(): void {
  if (typeof ResizeObserver === 'undefined') return
  const element = leftColumnEl.value
  if (element === null || element === observedLeftColumn) return
  leftColumnObserver ??= new ResizeObserver(syncModuleHeights)
  leftColumnObserver.disconnect()
  leftColumnObserver.observe(element)
  observedLeftColumn = element
}

onMounted(() => {
  window.addEventListener('resize', syncModuleHeights)
  observeLeftColumn()
  void bootstrap()
  void nextTick(syncModuleHeights)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncModuleHeights)
  leftColumnObserver?.disconnect()
})

watch(
  () => [motorStore.templatesReady, rightView.value, result.value],
  () => {
    void nextTick(() => {
      observeLeftColumn()
      syncModuleHeights()
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

        <PanelSection>
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
          <AppliedPanel v-else :min-height="panelContentMinHeight" />
        </PanelSection>
      </div>

      <div ref="rightColumnEl" class="col col-right">
        <PanelSection title="运动曲线">
          <CurvePanel />
        </PanelSection>

        <PanelSection title="选入电机数据">
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
