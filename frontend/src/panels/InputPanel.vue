<script setup lang="ts">
import { computed } from 'vue'
import PairRow from '@/components/PairRow.vue'
import { useCalculation } from '@/composables/useCalculation'
import { usePlanStore } from '@/stores/plan'
import { useProjectStore } from '@/stores/project'
import { useSettingStore } from '@/stores/setting'
import { formatNumber } from '@/utils/format'

/**
 * M4：工况输入（双列：用户输入 | 计算结果）。
 *
 * <p>纯 UI 规整（非负钳制、留空即自动、方向↔倾斜角回写）在组件内完成；
 * 业务公式仍只在服务端执行（A-1）。
 */
const project = useProjectStore()
const planStore = usePlanStore()
const setting = useSettingStore()
const { result } = useCalculation()

const isLinear = computed(() => project.axisKind === 'linear')
const isPointToPoint = computed(() => setting.mode === 'pointToPoint')
const velocityUnit = computed(() => (isLinear.value ? 'm/s' : 'rpm'))
const accelerationUnit = computed(() => (isLinear.value ? 'm/s²' : 'rpm/s'))
const resistanceUnit = computed(() => (isLinear.value ? 'N' : 'N·m'))
const forceUnit = computed(() => (isLinear.value ? 'N' : 'N·m'))
const loadUnit = computed(() => (isLinear.value ? 'kg' : 'kg·m²'))
const distanceLabel = computed(() => (isLinear.value ? '运动距离' : '旋转角度'))
const distanceUnit = computed(() => (isLinear.value ? 'm' : '°'))
const loadLabel = computed(() => (isLinear.value ? '总移动质量' : '负载惯量'))
const resistanceLabel = computed(() => (isLinear.value ? '阻力' : '摩擦力矩'))

const currentPlan = computed(() => {
  const book = planStore.books[project.axisKind]
  return book.plans[book.currentIndex] ?? book.plans[0]
})

const curve = computed(() => {
  const value = result.value
  return value && value.stage.ok ? value.curve : null
})

const direction = computed({
  get: () => currentPlan.value.direction,
  set: (value: 'up' | 'down') => {
    currentPlan.value.direction = value
    currentPlan.value.tiltDeg = value === 'down' ? 270 : 90
  },
})

function syncDirectionFromTilt(value: number | null | undefined): void {
  if (value == null || !Number.isFinite(value)) return
  const sin = Math.sin((value * Math.PI) / 180)
  if (Math.abs(sin) < 1e-9) return
  currentPlan.value.direction = sin < 0 ? 'down' : 'up'
}

/** 右侧计算列：有曲线时取计算结果，否则显示输入值本身。 */
function inputOrDash(value: number | null | undefined): string {
  return formatNumber(value, 4)
}
</script>

<template>
  <div class="pair-table">
    <div class="pair-head">
      <span></span>
      <span>用户输入</span>
      <span>计算结果</span>
      <span></span>
    </div>

    <PairRow :label="loadLabel" :unit="loadUnit" badge="required">
      <template #input>
        <el-input-number
          v-model="currentPlan.load"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空自动"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(currentPlan.load) }}</span>
      </template>
    </PairRow>

    <PairRow :label="distanceLabel" :unit="distanceUnit" badge="required">
      <template #input>
        <el-input-number
          v-model="currentPlan.distance"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空自动"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(currentPlan.distance) }}</span>
      </template>
    </PairRow>

    <PairRow
      label="运动时间"
      unit="s"
      :badge="isPointToPoint ? 'required' : 'optional'"
    >
      <template #input>
        <el-input-number
          v-model="currentPlan.time"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空自动"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value" :class="{ calc: !isPointToPoint }">
          {{ curve ? formatNumber(curve.time, 4) : '—' }}
        </span>
      </template>
    </PairRow>

    <PairRow
      label="最大加速度"
      :unit="accelerationUnit"
      :badge="isPointToPoint ? undefined : 'optional'"
    >
      <template #input>
        <div v-if="isPointToPoint" class="pair-readonly">—</div>
        <el-input-number
          v-else
          v-model="currentPlan.maxAcceleration"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空自动"
          class="pair-number"
        />
      </template>
      <template #result>
        <span
          class="pair-result-value"
          :class="{ calc: isPointToPoint || currentPlan.maxAcceleration == null }"
        >
          {{ curve ? formatNumber(curve.peakAcceleration, 4) : '—' }}
        </span>
      </template>
    </PairRow>

    <PairRow label="停顿时间" unit="s" badge="optional">
      <template #input>
        <el-input-number
          v-model="currentPlan.dwell"
          :min="0"
          :controls="false"
          size="small"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(currentPlan.dwell) }}</span>
      </template>
    </PairRow>

    <PairRow
      label="最大速度"
      :unit="velocityUnit"
      :badge="isPointToPoint ? undefined : 'optional'"
    >
      <template #input>
        <div v-if="isPointToPoint" class="pair-readonly">—</div>
        <el-input-number
          v-else
          v-model="currentPlan.maxVelocity"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空自动"
          class="pair-number"
        />
      </template>
      <template #result>
        <span
          class="pair-result-value"
          :class="{ calc: isPointToPoint || currentPlan.maxVelocity == null }"
        >
          {{ curve ? formatNumber(curve.peakVelocity, 4) : '—' }}
        </span>
      </template>
    </PairRow>

    <PairRow :label="resistanceLabel" :unit="resistanceUnit" badge="optional">
      <template #input>
        <el-input-number
          v-model="currentPlan.resistance"
          :min="isLinear ? -1000000 : 0"
          :controls="false"
          size="small"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(currentPlan.resistance) }}</span>
      </template>
    </PairRow>

    <div v-if="isLinear" class="direction-row">
      <span class="pair-label">方向</span>
      <el-radio-group v-model="direction" size="small">
        <el-radio value="up">上</el-radio>
        <el-radio value="down">下</el-radio>
      </el-radio-group>
      <span class="pair-hint-inline">仅作倾斜角快捷设定</span>
    </div>

    <PairRow
      label="倾斜角度"
      unit="°"
      badge="optional"
      :hint="isLinear ? '' : '旋转页为纯回转直驱模型，倾斜角度不参与计算'"
    >
      <template #input>
        <el-input-number
          v-model="currentPlan.tiltDeg"
          :min="0"
          :max="360"
          :controls="false"
          size="small"
          class="pair-number"
          @change="syncDirectionFromTilt"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(currentPlan.tiltDeg) }}</span>
      </template>
    </PairRow>

    <PairRow label="环境温度" unit="°C" badge="required">
      <template #input>
        <el-input-number
          v-model="currentPlan.ambientTemp"
          :min="-273"
          :controls="false"
          size="small"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(currentPlan.ambientTemp) }}</span>
      </template>
    </PairRow>

    <PairRow label="供电母线" unit="VDC" badge="optional">
      <template #input>
        <el-input-number
          v-model="currentPlan.supplyVoltage"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(currentPlan.supplyVoltage) }}</span>
      </template>
    </PairRow>

    <div class="pair-footnote">
      理论{{ forceUnit === 'N' ? '推力' : '力矩' }}只与左侧输入有关；右列为实际参与计算的值。
    </div>
  </div>
</template>
