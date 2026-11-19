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
    ctx.fillStyle = '#6f757e'
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
  ctx.fillStyle = '#6f757e'

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

  // 关键点标注：平台段标数值 + 拐点画实心点，让「这一刻是多少」不用去数纵轴
  drawKeyPoints(ctx, points, { xScale, yScale, padding, plotWidth, plotHeight, yMin, yMax })

  // 轴单位
  ctx.fillStyle = '#6f757e'
  ctx.textAlign = 'left'
  ctx.fillText(props.yUnit, padding.left, 10)
}

/** 平台段（数值恒定的一段）——匀速段的峰值速度、加减速段的恒力都落在这种段上。 */
interface Plateau {
  start: number
  end: number
  value: number
}

interface KeyPointContext {
  xScale: (t: number) => number
  yScale: (value: number) => number
  padding: { top: number; right: number; bottom: number; left: number }
  plotWidth: number
  plotHeight: number
  yMin: number
  yMax: number
}

/**
 * 关键点标注（第 63 轮）：
 * ①把折线拆成「平台段」，每段取中点标一个数值标签（白底彩色字，避免压在网格线上看不清）；
 * ②段与段之间数值突变处画实心圆点，即用户最关心的拐点；
 * ③标签按 |数值| 从大到小取前 3 个，并跳过水平重叠的，防止小图上堆字。
 */
function drawKeyPoints(
  ctx: CanvasRenderingContext2D,
  points: ChartPoint[],
  view: KeyPointContext,
): void {
  const { xScale, yScale, padding, plotWidth, yMin, yMax } = view
  const range = yMax - yMin
  if (points.length < 2 || range <= 0) return

  // ① 拆平台段：相邻采样值变化超过容差就切段
  const plateaus: Plateau[] = []
  let segStart = points[0].t
  let segValue = points[0].value
  let prevT = points[0].t
  points.forEach((point, index) => {
    if (index === 0) return
    const tol = 1e-9 + Math.abs(segValue) * 1e-6
    if (Math.abs(point.value - segValue) > tol) {
      plateaus.push({ start: segStart, end: prevT, value: segValue })
      segStart = point.t
      segValue = point.value
    }
    prevT = point.t
  })
  plateaus.push({ start: segStart, end: prevT, value: segValue })

  // ② 拐点：相邻居间数值跳变超过纵轴 8% 才算真拐点（S 曲线的细微过渡不画点）
  const corner = Math.max(range * 0.08, 1e-9)
  ctx.fillStyle = props.color
  for (let i = 1; i < plateaus.length; i += 1) {
    const prev = plateaus[i - 1].value
    const next = plateaus[i].value
    if (Math.abs(next - prev) < corner) continue
    const x = xScale(plateaus[i].start)
    const y = yScale(prev)
    ctx.beginPath()
    ctx.arc(x, y, 2.2, 0, Math.PI * 2)
    ctx.fill()
  }

  // ③ 数值标签：取最宽的若干平台段，按 |数值| 从大到小挑 3 个，互不重叠。
  //    斜坡段每个采样点自成一「段」（宽度只有 1~2px）会被 minWidth 滤掉，不会满屏标数。
  const minWidth = 26
  const candidates = plateaus
    .map((seg) => ({ seg, px: xScale(seg.end) - xScale(seg.start) }))
    .filter(({ px }) => px >= minWidth)
    .sort((a, b) => Math.abs(b.seg.value) - Math.abs(a.seg.value))

  const placed: Array<{ left: number; right: number }> = []
  let count = 0
  ctx.font = 'bold 11px "Microsoft YaHei", sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'

  for (const { seg } of candidates) {
    if (count >= 3) break
    const midT = (seg.start + seg.end) / 2
    const midX = xScale(midT)
    const y = yScale(seg.value)
    const text = `${formatNumber(seg.value, 2)} ${props.yUnit}`.trim()
    const textWidth = ctx.measureText(text).width
    const chipWidth = textWidth + 8
    const left = Math.min(
      Math.max(midX - chipWidth / 2, padding.left + 2),
      padding.left + plotWidth - chipWidth - 2,
    )
    const right = left + chipWidth
    if (placed.some((box) => left < box.right + 4 && right > box.left - 4)) continue

    // 正值标在线上方、负值标在线下方，尽量不压线
    const chipHeight = 14
    const above = seg.value >= 0
    const centerY = above ? y - 11 : y + 11
    const chipTop = Math.min(
      Math.max(centerY - chipHeight / 2, padding.top),
      padding.top + view.plotHeight - chipHeight,
    )

    ctx.fillStyle = 'rgba(255, 255, 255, 0.92)'
    roundedRect(ctx, left, chipTop, chipWidth, chipHeight, 3)
    ctx.fill()
    ctx.fillStyle = props.color
    ctx.fillText(text, left + chipWidth / 2, chipTop + chipHeight / 2 + 0.5)
    placed.push({ left, right })
    count += 1
  }
  ctx.textBaseline = 'alphabetic'
}

/** 圆角矩形路径（不用 ctx.roundRect：部分 TS lib 版本没有该声明）。 */
function roundedRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
): void {
  const r = Math.min(radius, width / 2, height / 2)
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + width - r, y)
  ctx.quadraticCurveTo(x + width, y, x + width, y + r)
  ctx.lineTo(x + width, y + height - r)
  ctx.quadraticCurveTo(x + width, y + height, x + width - r, y + height)
  ctx.lineTo(x + r, y + height)
  ctx.quadraticCurveTo(x, y + height, x, y + height - r)
  ctx.lineTo(x, y + r)
  ctx.quadraticCurveTo(x, y, x + r, y)
  ctx.closePath()
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
