<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { formatNumber } from '@/utils/format'

/**
 * K5：自研 Canvas 折线图（T-6）。
 *
 * <p>只负责绘制：折线、坐标网格、阶段分界竖线、水平参考线。
 * 数据来自 B1 的 sampling，服务端与报告共享同一份采样（02 §2.3.2）。
 */
interface ChartPoint {
  t: number
  value: number
}

interface ReferenceLine {
  value: number
  label: string
  color?: string
}

const props = withDefaults(
  defineProps<{
    caption: string
    points: ChartPoint[]
    yUnit: string
    color?: string
    referenceLines?: ReferenceLine[]
    stageLines?: number[]
    emptyText?: string
  }>(),
  {
    color: '#409eff',
    referenceLines: () => [],
    stageLines: () => [],
    emptyText: '暂无计算数据',
  },
)

const canvasRef = ref<HTMLCanvasElement | null>(null)
let observer: ResizeObserver | null = null

function draw(): void {
  const canvas = canvasRef.value
  if (!canvas) return

  const rect = canvas.getBoundingClientRect()
  const width = rect.width
  const height = rect.height
  if (width <= 0 || height <= 0) return

  const dpr = window.devicePixelRatio || 1
  canvas.width = Math.floor(width * dpr)
  canvas.height = Math.floor(height * dpr)

  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, width, height)

  const padding = { top: 14, right: 18, bottom: 24, left: 48 }
  const plotWidth = width - padding.left - padding.right
  const plotHeight = height - padding.top - padding.bottom

  const points = props.points
  if (points.length === 0) {
    ctx.fillStyle = '#909399'
    ctx.font = '12px "Microsoft YaHei", sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText(props.emptyText, padding.left + plotWidth / 2, padding.top + plotHeight / 2)
    return
  }

  const tMax = Math.max(...points.map((point) => point.t), 1e-9)
  const values = points.map((point) => point.value)
  const referenceValues = props.referenceLines.map((line) => line.value)
  let yMin = Math.min(0, ...values, ...referenceValues)
  let yMax = Math.max(0, ...values, ...referenceValues)
  if (yMax - yMin < 1e-9) {
    yMax = yMin + 1
  }
  const yPad = (yMax - yMin) * 0.08
  yMax += yPad
  if (yMin < 0) yMin -= yPad

  const xScale = (t: number): number => padding.left + (t / tMax) * plotWidth
  const yScale = (value: number): number =>
    padding.top + ((yMax - value) / (yMax - yMin)) * plotHeight

  // 网格与刻度
  ctx.strokeStyle = '#ebeef5'
  ctx.lineWidth = 1
  ctx.font = '11px "Microsoft YaHei", sans-serif'
  ctx.fillStyle = '#909399'

  for (let i = 0; i <= 4; i += 1) {
    const value = yMax - ((yMax - yMin) / 4) * i
    const y = yScale(value)
    ctx.beginPath()
    ctx.moveTo(padding.left, y)
    ctx.lineTo(padding.left + plotWidth, y)
    ctx.stroke()
    ctx.textAlign = 'right'
    ctx.fillText(formatNumber(value, 2), padding.left - 6, y + 3)
  }

  for (let i = 0; i <= 4; i += 1) {
    const t = (tMax / 4) * i
    const x = xScale(t)
    ctx.beginPath()
    ctx.moveTo(x, padding.top)
    ctx.lineTo(x, padding.top + plotHeight)
    ctx.stroke()
    ctx.textAlign = i === 0 ? 'left' : i === 4 ? 'right' : 'center'
    ctx.fillText(formatNumber(t, 2), x, height - 7)
  }

  // 阶段分界竖线
  ctx.setLineDash([4, 4])
  ctx.strokeStyle = '#c0c4cc'
  props.stageLines.forEach((t) => {
    if (t <= 0 || t >= tMax) return
    const x = xScale(t)
    ctx.beginPath()
    ctx.moveTo(x, padding.top)
    ctx.lineTo(x, padding.top + plotHeight)
    ctx.stroke()
  })

  // 水平参考线
  props.referenceLines.forEach((line) => {
    const y = yScale(line.value)
    ctx.strokeStyle = line.color ?? '#e6a23c'
    ctx.beginPath()
    ctx.moveTo(padding.left, y)
    ctx.lineTo(padding.left + plotWidth, y)
    ctx.stroke()

    ctx.setLineDash([])
    ctx.fillStyle = line.color ?? '#e6a23c'
    ctx.textAlign = 'right'
    ctx.fillText(line.label, padding.left + plotWidth - 2, y - 3)
  })

  // 数据折线
  ctx.setLineDash([])
  ctx.strokeStyle = props.color
  ctx.lineWidth = 1.8
  ctx.beginPath()
  points.forEach((point, index) => {
    const x = xScale(point.t)
    const y = yScale(point.value)
    if (index === 0) {
      ctx.moveTo(x, y)
    } else {
      ctx.lineTo(x, y)
    }
  })
  ctx.stroke()

  // 轴单位
  ctx.fillStyle = '#909399'
  ctx.textAlign = 'left'
  ctx.fillText(props.yUnit, padding.left, 10)
}

watch(
  [() => props.points, () => props.referenceLines, () => props.stageLines, () => props.color],
  () => draw(),
)

onMounted(() => {
  draw()
  if (canvasRef.value) {
    observer = new ResizeObserver(() => draw())
    observer.observe(canvasRef.value)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div class="chart-block">
    <canvas ref="canvasRef" class="chart-canvas" role="img" :aria-label="props.caption" />
  </div>
</template>
