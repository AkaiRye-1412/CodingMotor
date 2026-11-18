<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
import { ApiError } from '@/types/api'

/**
 * 页面骨架：三列布局与面板顺序按需求 8.2 保持不变（02 5.2.1）。
 * 字段模板是 M10 表单与 M8 参数表的必需数据，加载失败时阻塞启动并提供重试。
 */
const motorStore = useMotorStore()
const bootError = ref('')
const rightView = ref<'candidates' | 'motor'>('candidates')

// 激活 K4 调度：输入变化 → B1 请求（防抖/取消/序号）
useAutoCalculation()

// 激活 K4 调度：输入变化 → B1 请求（防抖/取消/序号）
useAutoCalculation()

// 激活 K4 调度：输入变化 → B1 请求（防抖/取消/序号）
useAutoCalculation()

async function bootstrap() {
  bootError.value = ''
  try {
    await motorStore.loadTemplates()
  } catch (error) {
    bootError.value =
      error instanceof ApiError ? error.message : '字段模板加载失败，请确认本地服务已启动'
  }
}

onMounted(bootstrap)
</script>

<template>
  <div class="app-shell">
    <TopBar />

    <main v-if="motorStore.templatesReady" class="workspace">
      <div class="col col-left">
        <PanelSection title="运动模式">
          <ModePanel />
        </PanelSection>
        <PanelSection title="方案管理">
          <PlanPanel />
        </PanelSection>
        <PanelSection title="用户输入" hint="双列：输入 / 计算结果">
          <InputPanel />
        </PanelSection>
      </div>

      <div class="col col-middle">
        <PanelSection title="安全余量">
          <MarginPanel />
        </PanelSection>
        <PanelSection title="理论计算结果">
          <ResultPanel />
        </PanelSection>
        <PanelSection title="电机实际应用值">
          <AppliedPanel />
        </PanelSection>
      </div>

      <div class="col col-right">
        <PanelSection>
          <template #head>
            <div class="view-toggle">
              <el-radio-group v-model="rightView" size="small">
                <el-radio-button value="candidates">推荐电机</el-radio-button>
                <el-radio-button value="motor">选入电机数据</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <CandidatePanel v-if="rightView === 'candidates'" />
          <MotorPanel v-else />
        </PanelSection>

        <PanelSection title="运动曲线">
          <CurvePanel />
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
