#!/usr/bin/env node
/*
 * 黄金快照生成器（设计 02 R-2「快照冻结」落地）
 *
 * 用途：
 *   用冻结的原型计算内核 prototype/js/calc.js 跑一批固定工况，把期望值写入
 *   motorsizer-domain/src/test/resources/golden/*.json，供 Java 单测逐项比对。
 *
 * 关键约束：
 *   1. 本脚本只在需要「重新生成基线」时手动运行，构建链与 Java 单测不依赖 Node。
 *   2. prototype/js/calc.js 是基线，禁止修改；若确需修改，必须重新生成快照并评审 diff。
 *   3. 快照中的 fixture 电机来自 prototype/js/data.js 的演示库，仅作测试夹具，不进产品。
 *
 * 运行：node scripts/gen-golden.js
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')

const ROOT = path.resolve(__dirname, '..')
const PROTOTYPE = path.join(ROOT, 'prototype')
const OUT_DIR = path.join(ROOT, 'motorsizer-domain', 'src', 'test', 'resources', 'golden')

global.window = global
require(path.join(PROTOTYPE, 'js', 'data.js'))
require(path.join(PROTOTYPE, 'js', 'calc.js'))

const C = global.CALC
const DATA = global.APP_DATA

const FROZEN_AT = '2026-11-18'
const TOLERANCE = 1e-9

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

const META = {
  generator: 'scripts/gen-golden.js',
  baseline: 'prototype/js/calc.js',
  baselineSha256: sha256(path.join(PROTOTYPE, 'js', 'calc.js')),
  fixturesSource: 'prototype/js/data.js（演示电机，仅作测试夹具）',
  fixturesSha256: sha256(path.join(PROTOTYPE, 'js', 'data.js')),
  frozenAt: FROZEN_AT,
  tolerance: TOLERANCE,
  note:
    '快照由 Node 一次性生成后冻结；Java 单测只读这些 JSON 比对，构建链不依赖 Node。' +
    '修改 calc.js 或 data.js 后必须重新运行 node scripts/gen-golden.js 并评审快照差异。',
}

/* ---------------- 通用构造 ---------------- */

const p2p = (dist, time, shape = 'trapezoid') => ({
  fn: 'planPointToPoint',
  input: { dist, time, shape },
})

const custom = (input) => ({ fn: 'planCustom', input })

function buildCurve(spec) {
  if (spec.fn === 'planPointToPoint') return C.planPointToPoint(spec.input)
  if (spec.fn === 'planCustom') return C.planCustom(spec.input)
  throw new Error(`未知规划函数: ${spec.fn}`)
}

/** 按 plan + 可选 τ 得到曲线；失败时返回原错误对象。 */
function buildSmoothed(planSpec) {
  const curve = buildCurve(planSpec)
  if (!curve.ok) return curve
  if (planSpec.tauMs == null) return curve

  const smoothed = C.applySmooth(curve, planSpec.tauMs)
  if (!smoothed.ok) return smoothed
  return smoothed.curve
}

const MOTOR_IDS = [
  'ILM-12-020',
  'ILM-12-040',
  'ILM-20-050',
  'ADR110-B136 / 串联',
  'ADR110-B105 / 串联',
  'AVM12-6.4 / 默认',
]

function motorById(id) {
  const found = DATA.motors.list.find((motor) => motor.id === id)
  if (!found) throw new Error(`演示电机中不存在: ${id}`)
  return found
}

const FIXTURES = Object.fromEntries(MOTOR_IDS.map((id) => [id, motorById(id)]))

/* ---------------- 1) motion ---------------- */

const POINT_TO_POINT_CASES = [
  { id: 'p2p-trapezoid-030-05', input: p2p(0.3, 0.5).input },
  { id: 'p2p-triangle-030-05', input: p2p(0.3, 0.5, 'triangle').input },
  { id: 'p2p-default-shape-030-05', input: { dist: 0.3, time: 0.5 } },
  { id: 'p2p-gravity-100-1', input: p2p(1, 1).input },
  { id: 'p2p-rotary-180-05', input: p2p(180, 0.5).input },
  { id: 'p2p-invalid-dist-zero', input: { dist: 0, time: 1, shape: 'trapezoid' } },
  { id: 'p2p-invalid-time-zero', input: { dist: 1, time: 0, shape: 'trapezoid' } },
]

const CUSTOM_CASES = [
  { id: 'custom-triangle-1-1-a4-v2', input: { dist: 1, time: 1, aMax: 4, vMax: 2 } },
  { id: 'custom-trapezoid-2-1-a9-v3', input: { dist: 2, time: 1, aMax: 9, vMax: 3 } },
  { id: 'custom-auto-v-2-1-a10', input: { dist: 2, time: 1, aMax: 10 } },
  { id: 'custom-only-time-030-05', input: { dist: 0.3, time: 0.5 } },
  { id: 'custom-auto-time-1-v2-a4', input: { dist: 1, vMax: 2, aMax: 4 } },
  { id: 'custom-only-accel-1-a4', input: { dist: 1, aMax: 4 } },
  { id: 'custom-only-velocity-error-1-v2', input: { dist: 1, vMax: 2 } },
  { id: 'custom-low-velocity-1-1-v19', input: { dist: 1, time: 1, vMax: 1.9 } },
  { id: 'custom-accel-conflict-1-1-a39', input: { dist: 1, time: 1, aMax: 3.9 } },
  { id: 'custom-velocity-conflict-1-1-v09', input: { dist: 1, time: 1, vMax: 0.9 } },
  { id: 'custom-both-conflict-1-1-a5-v12', input: { dist: 1, time: 1, aMax: 5, vMax: 1.2 } },
  { id: 'custom-invalid-dist', input: { dist: 0, time: 1 } },
]

const SMOOTHING_CASES = [
  { id: 'smooth-tau0-p2p-030-05', plan: p2p(0.3, 0.5), tauMs: 0 },
  { id: 'smooth-tau4-p2p-030-05', plan: p2p(0.3, 0.5), tauMs: 4 },
  { id: 'smooth-tau16-p2p-030-05', plan: p2p(0.3, 0.5), tauMs: 16 },
  { id: 'smooth-tau56-p2p-030-05', plan: p2p(0.3, 0.5), tauMs: 56 },
  { id: 'smooth-tau16-custom-triangle', plan: custom({ dist: 1, time: 1, aMax: 4, vMax: 2 }), tauMs: 16 },
  { id: 'smooth-infeasible-tau56-p2p-010-02', plan: p2p(0.1, 0.2), tauMs: 56 },
]

function motionPayload() {
  const pointToPoint = POINT_TO_POINT_CASES.map((testCase) => ({
    id: testCase.id,
    input: testCase.input,
    result: C.planPointToPoint(testCase.input),
  }))

  const customCases = CUSTOM_CASES.map((testCase) => ({
    id: testCase.id,
    input: testCase.input,
    result: C.planCustom(testCase.input),
  }))

  const smoothing = SMOOTHING_CASES.map((testCase) => {
    const base = buildCurve(testCase.plan)
    return {
      id: testCase.id,
      basePlan: testCase.plan,
      tauMs: testCase.tauMs,
      result: base.ok ? C.applySmooth(base, testCase.tauMs) : base,
    }
  })

  return { meta: META, pointToPoint, custom: customCases, smoothing }
}

/* ---------------- 2) theoretical ---------------- */

const THEORETICAL_CASES = [
  {
    id: 'linear-8kg-1m-1s-tilt90',
    kind: 'linear',
    plan: p2p(1, 1),
    load: 8,
    resist: 0,
    env: { dwell: 0, tilt: 90 },
  },
  {
    id: 'linear-8kg-1m-1s-tilt270',
    kind: 'linear',
    plan: p2p(1, 1),
    load: 8,
    resist: 0,
    env: { dwell: 0, tilt: 270 },
  },
  {
    id: 'linear-8kg-1m-1s-tilt0',
    kind: 'linear',
    plan: p2p(1, 1),
    load: 8,
    resist: 0,
    env: { dwell: 0, tilt: 0 },
  },
  {
    id: 'linear-8kg-1m-1s-resist10-dwell03',
    kind: 'linear',
    plan: p2p(1, 1),
    load: 8,
    resist: 10,
    env: { dwell: 0.3, tilt: 0 },
  },
  {
    id: 'linear-2kg-1m-1s-tilt0',
    kind: 'linear',
    plan: p2p(1, 1),
    load: 2,
    resist: 0,
    env: { dwell: 0, tilt: 0 },
  },
  {
    id: 'linear-2kg-1m-1s-tilt90',
    kind: 'linear',
    plan: p2p(1, 1),
    load: 2,
    resist: 0,
    env: { dwell: 0, tilt: 90 },
  },
  {
    id: 'linear-0kg-10m-1s-tilt0',
    kind: 'linear',
    plan: p2p(10, 1),
    load: 0,
    resist: 0,
    env: { dwell: 0, tilt: 0 },
  },
  {
    id: 'linear-smooth-030-05-tau16',
    kind: 'linear',
    plan: { ...p2p(0.3, 0.5), tauMs: 16 },
    load: 2.5,
    resist: 1,
    env: { dwell: 0.2, tilt: 0 },
  },
  {
    id: 'rotary-360-06-tilt90',
    kind: 'rotary',
    plan: p2p(360, 0.6),
    load: 0.05,
    resist: 0.5,
    env: { dwell: 0, tilt: 90 },
  },
  {
    id: 'coil-linear-030-05-tilt0',
    kind: 'linear',
    plan: p2p(0.3, 0.5),
    load: 0.5,
    resist: 0,
    env: { dwell: 0, tilt: 0 },
  },
]

function computeTheoretical(testCase) {
  const curve = buildSmoothed(testCase.plan)
  if (!curve.ok) {
    return { curve, theoretical: null }
  }
  return {
    curve,
    theoretical: C.theoretical(
      testCase.kind,
      testCase.load,
      curve,
      testCase.resist ?? 0,
      testCase.env ?? {},
    ),
  }
}

const THEORETICAL_RESULTS = new Map()

function theoreticalPayload() {
  const cases = THEORETICAL_CASES.map((testCase) => {
    const computed = computeTheoretical(testCase)
    THEORETICAL_RESULTS.set(testCase.id, computed)
    return {
      id: testCase.id,
      kind: testCase.kind,
      plan: testCase.plan,
      load: testCase.load,
      resist: testCase.resist ?? 0,
      env: testCase.env ?? {},
      result: computed.theoretical,
      error: computed.theoretical ? null : computed.curve,
    }
  })
  return { meta: META, cases }
}

/* ---------------- 3) screening ---------------- */

const SCREENING_CASES = [
  {
    id: 'linear-ilm12020-2kg-pass',
    kind: 'linear',
    motorId: 'ILM-12-020',
    theoreticalRef: 'linear-2kg-1m-1s-tilt0',
    marginLow: 0.3,
  },
  {
    id: 'linear-ilm12040-2kg-pass',
    kind: 'linear',
    motorId: 'ILM-12-040',
    theoreticalRef: 'linear-2kg-1m-1s-tilt0',
    marginLow: 0.3,
  },
  {
    id: 'linear-ilm12020-8kg-fail',
    kind: 'linear',
    motorId: 'ILM-12-020',
    theoreticalRef: 'linear-8kg-1m-1s-tilt90',
    marginLow: 0.3,
  },
  {
    id: 'linear-ilm20050-missing-thermal',
    kind: 'linear',
    motorId: 'ILM-20-050',
    theoreticalRef: 'linear-2kg-1m-1s-tilt0',
    marginLow: 0.3,
  },
  {
    id: 'linear-ilm12020-speed-fail',
    kind: 'linear',
    motorId: 'ILM-12-020',
    theoreticalRef: 'linear-0kg-10m-1s-tilt0',
    marginLow: 0.3,
  },
  {
    id: 'linear-ilm12020-margin0',
    kind: 'linear',
    motorId: 'ILM-12-020',
    theoreticalRef: 'linear-2kg-1m-1s-tilt0',
    marginLow: 0,
  },
  {
    id: 'rotary-adr110b136-series',
    kind: 'rotary',
    motorId: 'ADR110-B136 / 串联',
    theoreticalRef: 'rotary-360-06-tilt90',
    marginLow: 0.3,
  },
  {
    id: 'rotary-adr110b105-series',
    kind: 'rotary',
    motorId: 'ADR110-B105 / 串联',
    theoreticalRef: 'rotary-360-06-tilt90',
    marginLow: 0.3,
  },
  {
    id: 'coil-avm12-speed-skipped',
    kind: 'linear',
    motorId: 'AVM12-6.4 / 默认',
    theoreticalRef: 'coil-linear-030-05-tilt0',
    marginLow: 0.3,
  },
]

function screeningPayload() {
  const cases = SCREENING_CASES.map((testCase) => {
    const computed = THEORETICAL_RESULTS.get(testCase.theoreticalRef)
    if (!computed || !computed.theoretical) {
      throw new Error(`筛选用例引用了不可算的理论用例: ${testCase.theoreticalRef}`)
    }
    const result = C.evaluateMotor(
      testCase.kind,
      FIXTURES[testCase.motorId],
      computed.theoretical,
      computed.curve,
      testCase.marginLow,
    )
    const { motor, ...rest } = result
    return {
      id: testCase.id,
      kind: testCase.kind,
      motorId: testCase.motorId,
      theoreticalRef: testCase.theoreticalRef,
      marginLow: testCase.marginLow,
      result: rest,
    }
  })
  return { meta: META, cases }
}

/* ---------------- 4) applied ---------------- */

const APPLIED_CASES = [
  {
    id: 'rotary-adr110b136',
    kind: 'rotary',
    motorId: 'ADR110-B136 / 串联',
    theoreticalRef: 'rotary-360-06-tilt90',
    env: { load: 0.05, resist: 0.5, dwell: 0.3, tAmbient: 22, uSupply: 311, marginLow: 0.3, tilt: 90 },
  },
  {
    id: 'linear-ilm12020-2kg',
    kind: 'linear',
    motorId: 'ILM-12-020',
    theoreticalRef: 'linear-2kg-1m-1s-tilt0',
    env: { load: 2, resist: 0, dwell: 0.3, tAmbient: 22.5, uSupply: 311, marginLow: 0.3, tilt: 0 },
  },
  {
    id: 'linear-ilm12020-2kg-gravity-up',
    kind: 'linear',
    motorId: 'ILM-12-020',
    theoreticalRef: 'linear-2kg-1m-1s-tilt90',
    env: { load: 2, resist: 0, dwell: 0.3, tAmbient: 22.5, uSupply: 311, marginLow: 0.3, tilt: 90 },
  },
  {
    id: 'linear-ilm20050-missing-thermal',
    kind: 'linear',
    motorId: 'ILM-20-050',
    theoreticalRef: 'linear-2kg-1m-1s-tilt0',
    env: { load: 2, resist: 0, dwell: 0.3, tAmbient: 22.5, uSupply: 311, marginLow: 0.3, tilt: 0 },
  },
  {
    id: 'linear-ilm20050-no-supply',
    kind: 'linear',
    motorId: 'ILM-20-050',
    theoreticalRef: 'linear-2kg-1m-1s-tilt0',
    env: { load: 2, resist: 0, dwell: 0.3, tAmbient: 22.5, uSupply: null, marginLow: 0.3, tilt: 0 },
  },
  {
    id: 'coil-avm12',
    kind: 'linear',
    motorId: 'AVM12-6.4 / 默认',
    theoreticalRef: 'coil-linear-030-05-tilt0',
    env: { load: 0.5, resist: 0, dwell: 0, tAmbient: 22, uSupply: 60, marginLow: 0.3, tilt: 0 },
  },
]

function appliedPayload() {
  const cases = APPLIED_CASES.map((testCase) => {
    const computed = THEORETICAL_RESULTS.get(testCase.theoreticalRef)
    if (!computed || !computed.theoretical) {
      throw new Error(`应用值用例引用了不可算的理论用例: ${testCase.theoreticalRef}`)
    }
    return {
      id: testCase.id,
      kind: testCase.kind,
      motorId: testCase.motorId,
      theoreticalRef: testCase.theoreticalRef,
      env: testCase.env,
      result: C.appliedValue(
        testCase.kind,
        FIXTURES[testCase.motorId],
        computed.theoretical,
        computed.curve,
        testCase.env,
      ),
    }
  })
  return { meta: META, cases }
}

/* ---------------- 5) sampling ---------------- */

const SAMPLING_CASES = [
  {
    id: 'p2p-1m-1s-trapezoid-nosmooth',
    plan: p2p(1, 1),
    force: { mass: 8, resist: 0, gravity: 78.48 },
  },
  {
    id: 'p2p-030-05-tau16',
    plan: { ...p2p(0.3, 0.5), tauMs: 16 },
    force: { mass: 2, resist: 0, gravity: 0 },
  },
  {
    id: 'custom-triangle-1-1-a4-v2',
    plan: custom({ dist: 1, time: 1, aMax: 4, vMax: 2 }),
    force: { mass: 1, resist: 0, gravity: 0 },
  },
]

function samplingPayload() {
  const cases = SAMPLING_CASES.map((testCase) => {
    const curve = buildSmoothed(testCase.plan)
    if (!curve.ok) throw new Error(`采样用例曲线不可算: ${testCase.id}`)

    const forceFn = (acc) => testCase.force.mass * acc + testCase.force.resist + testCase.force.gravity
    const sampled = C.sampleCurve(curve, forceFn)

    return {
      id: testCase.id,
      plan: testCase.plan,
      forceModel: testCase.force,
      pointCount: sampled.vPts.length,
      t: sampled.vPts.map((point) => point.t),
      v: sampled.vPts.map((point) => point.v),
      a: sampled.vPts.map((point) => point.a),
      f: sampled.fPts.map((point) => point.f),
    }
  })
  return { meta: META, cases }
}

/* ---------------- 6) formatting ---------------- */

const FMT_CASES = [
  { value: 1.23456, n: 3 },
  { value: 1.2, n: 3 },
  { value: 0, n: 3 },
  { value: 0.0001234, n: 3 },
  { value: 0.0009, n: 3 },
  { value: -0.0009, n: 3 },
  { value: 123.0, n: 3 },
  { value: 1.23456, n: null },
]

const ROUND_CASES = [
  { value: 1.23456, n: 3 },
  { value: 1.005, n: 2 },
  { value: -1.005, n: 2 },
  { value: null, n: 2 },
]

const RMS_CASES = [
  { id: 'normal', args: [10, 0.1, 5, 0.2, 8, 0.1, 0.3] },
  { id: 'zero-total', args: [1, 0, 2, 0, 3, 0, 0] },
  { id: 'no-cruise', args: [10, 0.2, 0, 0, 10, 0.2, 0.1] },
]

const REDLINE_CASES = [
  { id: 'linear-pass', kind: 'linear', plan: p2p(0.3, 0.5), vMax: 2, aMax: 6 },
  { id: 'linear-speed-over', kind: 'linear', plan: p2p(1, 0.5), vMax: 2, aMax: 100 },
  { id: 'linear-accel-over', kind: 'linear', plan: p2p(0.3, 0.5), vMax: 100, aMax: 5 },
  { id: 'rotary-speed-over', kind: 'rotary', plan: p2p(360, 0.6), vMax: 100, aMax: 100000 },
]

function formattingPayload() {
  return {
    meta: META,
    constants: { ALPHA_CU: C.ALPHA_CU, PRESETS: C.PRESETS },
    fmt: FMT_CASES.map((testCase) => ({
      value: testCase.value,
      n: testCase.n,
      result: C.fmt(testCase.value, testCase.n),
    })),
    round: ROUND_CASES.map((testCase) => ({
      value: testCase.value,
      n: testCase.n,
      result: C.round(testCase.value, testCase.n),
    })),
    units: {
      linear: { speedDisplayOf: C.speedDisplay('linear', 1.5), accelFactor: C.accelFactor('linear') },
      rotary: {
        speedDisplayOf: C.speedDisplay('rotary', 6),
        speedDisplayOfCurve: C.speedDisplay('rotary', 540),
        accelFactor: C.accelFactor('rotary'),
        accelDisplayOf: C.accelDisplay('rotary', 10),
      },
    },
    rmsOfCycle: RMS_CASES.map((testCase) => ({
      id: testCase.id,
      args: testCase.args,
      result: C.rmsOfCycle(...testCase.args),
    })),
    redline: REDLINE_CASES.map((testCase) => {
      const curve = buildCurve(testCase.plan)
      return {
        id: testCase.id,
        kind: testCase.kind,
        plan: testCase.plan,
        vMax: testCase.vMax,
        aMax: testCase.aMax,
        result: curve.ok ? C.redline(testCase.kind, curve, testCase.vMax, testCase.aMax) : curve,
      }
    }),
    closestPreset: [ { maxTau: 50 }, { maxTau: 3 }, { maxTau: 0 }, { maxTau: 100 }, { maxTau: 16 } ]
      .map((testCase) => ({ ...testCase, result: C.closestPreset(testCase.maxTau) })),
    requiredMissing: MOTOR_IDS.map((id) => ({
      motorId: id,
      result: C.requiredMissing(FIXTURES[id]),
    })),
  }
}

/* ---------------- 输出 ---------------- */

function writeJson(name, payload) {
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(path.join(OUT_DIR, name), `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
}

function main() {
  writeJson('fixtures.json', {
    meta: META,
    motors: FIXTURES,
  })
  writeJson('motion.json', motionPayload())
  writeJson('theoretical.json', theoreticalPayload())
  writeJson('screening.json', screeningPayload())
  writeJson('applied.json', appliedPayload())
  writeJson('sampling.json', samplingPayload())
  writeJson('formatting.json', formattingPayload())

  console.log(`黄金快照已生成：${OUT_DIR}`)
  console.log(`calc.js sha256 = ${META.baselineSha256}`)
}

main()
