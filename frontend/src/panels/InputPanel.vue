<script setup lang="ts">
import { computed } from 'vue'
import PairRow from '@/components/PairRow.vue'
import { useCalculation } from '@/composables/useCalculation'
import { useMotorStore } from '@/stores/motor'
import { usePlanStore } from '@/stores/plan'
import { useProjectStore } from '@/stores/project'
import { useSettingStore } from '@/stores/setting'
import { formatShort } from '@/utils/format'

/** M4 里可编辑的数值字段（用于失焦收敛小数位）。 */
type NumericPlanKey =
  | 'load'
  | 'distance'
  | 'time'
  | 'dwell'
  | 'maxVelocity'
  | 'maxAcceleration'
  | 'resistance'
  | 'tiltDeg'
  | 'ambientTemp'
  | 'supplyVoltage'

/**
 * M4：工况输入（双列：用户输入 | 计算结果）。
 *
 * <p>纯 UI 规整（非负钳制、留空即自动、方向↔倾斜角回写）在组件内完成；
 * 业务公式仍只在服务端执行（A-1）。
 */
const project = useProjectStore()
const planStore = usePlanStore()
const setting = useSettingStore()
const motorStore = useMotorStore()
const { result } = useCalculation()

const isLinear = computed(() => project.axisKind === 'linear')
const isPointToPoint = computed(() => setting.mode === 'pointToPoint')
const velocityUnit = computed(() => (isLinear.value ? 'm/s' : 'rpm'))
const accelerationUnit = computed(() => (isLinear.value ? 'm/s²' : 'rpm/s'))
const resistanceUnit = computed(() => (isLinear.value ? 'N' : 'N·m'))
const loadUnit = computed(() => (isLinear.value ? 'kg' : 'kg·m²'))
const distanceLabel = computed(() => (isLinear.value ? '运动距离' : '旋转角度'))
const distanceUnit = computed(() => (isLinear.value ? 'm' : '°'))
const loadLabel = computed(() => (isLinear.value ? '总移动质量' : '负载惯量'))
const resistanceLabel = computed(() => (isLinear.value ? '阻力' : '摩擦力矩'))
/** CAL-26：直线列线圈质量、旋转列电机惯量（默认 0，选中电机后自动带入）。 */
const motorMassLabel = computed(() => (isLinear.value ? '线圈质量' : '电机惯量'))
const motorMassUnit = computed(() => (isLinear.value ? 'kg' : 'kg·m²'))

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

/**
 * 输入框失焦后把数值收敛到 3 位小数（面板左栏只有约 60px，写不下更多小数）。
 *
 * <p>不补零（`1` 仍是 `1`，不会变成 `1.000`）；若收敛后会被抹成 0（例如 `0.0004`），
 * 保留原精度，避免把真实的小值抹平（此时列表会多显示一位）。
 */
function fixDigits(
  target: Partial<Record<NumericPlanKey, number | null>>,
  key: NumericPlanKey,
): void {
  const value = target[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) return
  const rounded = Math.round(value * 1000) / 1000
  if (rounded !== 0 || value === 0) {
    target[key] = rounded
  }
}

function handleTiltChange(value: number | null | undefined): void {
  fixDigits(currentPlan.value, 'tiltDeg')
  syncDirectionFromTilt(currentPlan.value.tiltDeg ?? value)
}

/** 右侧计算列：与左栏同口径（3 位小数，多余的位不显示）。 */
function inputOrDash(value: number | null | undefined): string {
  return formatShort(value, 3)
}

/** 实际参与计算的总负载 = 用户输入 + 选中电机自身质量/惯量（CAL-26）。 */
const totalLoad = computed<number | null>(() => {
  const load = currentPlan.value.load
  if (load == null || !Number.isFinite(load)) return null
  return load + (motorStore.selectedMotorMass || 0)
})

/**
 * 只读展示的电机自身质量/惯量：未选电机、或该电机未录入该项时一律显示「—」，
 * 免得把「0」误读成真实参数。真正参与计算的 0 由 store 里维持。
 */
const motorMassValue = computed<number | null>(() =>
  motorStore.selectedMotorId == null || motorStore.selectedMotorMass <= 0
    ? null
    : motorStore.selectedMotorMass,
)

const motorMassHint = computed(() => {
  if (motorStore.motorMassLoading) return '正在读取所选电机的数据…'
  if (motorStore.selectedMotorId != null && motorMassValue.value == null) {
    return `所选电机未录入${motorMassLabel.value}，合计仅含用户输入`
  }
  return ''
})
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
          @change="fixDigits(currentPlan, 'load')"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空自动"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value">{{ inputOrDash(totalLoad) }}</span>
      </template>
    </PairRow>

    <PairRow :label="motorMassLabel" :unit="motorMassUnit" :hint="motorMassHint">
      <template #input>
        <div class="pair-readonly">—</div>
      </template>
      <template #result>
        <span class="pair-result-value" :class="{ calc: motorMassValue != null }">
          {{ inputOrDash(motorMassValue) }}
        </span>
      </template>
    </PairRow>

    <PairRow :label="distanceLabel" :unit="distanceUnit" badge="required">
      <template #input>
        <el-input-number
          v-model="currentPlan.distance"
          @change="fixDigits(currentPlan, 'distance')"
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
          @change="fixDigits(currentPlan, 'time')"
          :min="0"
          :controls="false"
          size="small"
          placeholder="留空自动"
          class="pair-number"
        />
      </template>
      <template #result>
        <span class="pair-result-value" :class="{ calc: !isPointToPoint }">
          {{ curve ? formatShort(curve.time, 3) : '—' }}
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
          @change="fixDigits(currentPlan, 'maxAcceleration')"
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
          {{ curve ? formatShort(curve.peakAcceleration, 3) : '—' }}
        </span>
      </template>
    </PairRow>

    <PairRow label="停顿时间" unit="s" badge="optional">
      <template #input>
        <el-input-number
          v-model="currentPlan.dwell"
          @change="fixDigits(currentPlan, 'dwell')"
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
          @change="fixDigits(currentPlan, 'maxVelocity')"
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
          {{ curve ? formatShort(curve.peakVelocity, 3) : '—' }}
        </span>
      </template>
    </PairRow>

    <PairRow :label="resistanceLabel" :unit="resistanceUnit" badge="optional">
      <template #input>
        <el-input-number
          v-model="currentPlan.resistance"
          @change="fixDigits(currentPlan, 'resistance')"
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
          @change="handleTiltChange"
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
          @change="fixDigits(currentPlan, 'ambientTemp')"
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
          @change="fixDigits(currentPlan, 'supplyVoltage')"
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
  </div>
</template>
