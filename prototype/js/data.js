/* 电机选型工具 — 原型示例数据
 * 依据 doc/requirements.md 第 6 章（电机库字段字典）构造示例数据。
 * 参数取自厂商样册样例的字段结构与量级，仅用于交互演示，不代表真实产品参数。
 *
 * 第 38 轮：电机类型拆为 直线 / 力矩 / 音圈 三种，字段模板按各自样册分叉；
 *          电机库统一为一份 list，候选列表混排，用「电机类型」勾选过滤。
 */
window.APP_DATA = (function () {
  'use strict';

  /**
   * 电机类型字典
   *   axis：该类型参与哪个页签（直线/旋转）的计算——音圈是直线运动，推力 N、速度 m/s
   *   configs / structures / coolings / bores：录入表单的下拉选项，随类型变化
   *   configs 取值顺序即下拉顺序，第一项为默认值（音圈为单线圈，无串并联接法）
   */
  const MOTOR_TYPES = {
    linear: {
      key: 'linear', label: '直线', axis: 'linear',
      configs: ['默认', '串联', '并联'],
      structures: ['无铁芯', '有铁芯'], coolings: ['普通', '水冷', '气冷'], bores: []
    },
    torque: {
      key: 'torque', label: '力矩', axis: 'rotary',
      configs: ['串联', '并联', '默认'],
      structures: ['有铁芯', '无铁芯', '超声波力矩电机'], coolings: ['普通', '气冷', '水冷'], bores: ['标准', '特殊']
    },
    coil: {
      key: 'coil', label: '音圈', axis: 'linear',
      configs: ['默认'],
      structures: ['音圈'], coolings: ['普通'], bores: []
    }
  };
  const TYPE_ORDER = ['linear', 'torque', 'coil'];

  /** 界面字段模板：直线页 / 旋转页结构同构，仅标签与单位不同 */
  const schema = {
    linear: {
      kind: 'linear',
      label: '直线马达',
      loadLabel: '总移动质量',
      loadUnit: 'kg',
      // 第 45 轮：与参考软件一致，电机线圈质量不再叠加；用户填的总移动质量即为计算口径
      loadHint: '填写总移动质量（不含电机线圈）',
      distLabel: '运动距离',
      distUnit: 'm',
      vLabel: '最大速度',
      vUnit: 'm/s',
      aLabel: '最大加速度',
      aUnit: 'm/s²',
      resistLabel: '阻力',
      resistUnit: 'N',
      forceLabel: '推力',
      forceUnit: 'N',
      currentLabel: '电流',
      currentUnit: 'A',
      speedFieldLabel: '最高速度',
      speedFieldUnit: 'm/s',
      hasDirection: true,
      units: { vChart: 'Velocity (m/s)', fChart: 'Force (N)' }
    },
    rotary: {
      kind: 'rotary',
      label: '旋转马达',
      loadLabel: '负载惯量',
      loadUnit: 'kg·m²',
      loadHint: '按「已折算到电机轴」口径填写',
      distLabel: '旋转角度',
      distUnit: '°',
      vLabel: '最大速度',
      vUnit: 'rpm',
      aLabel: '最大加速度',
      aUnit: 'rpm/s',
      resistLabel: '摩擦力矩',
      resistUnit: 'N·m',
      forceLabel: '力矩',
      forceUnit: 'N·m',
      currentLabel: '电流',
      currentUnit: 'A',
      speedFieldLabel: '最高转速',
      speedFieldUnit: 'rpm',
      hasDirection: false,
      units: { vChart: 'Velocity (rpm)', fChart: 'Torque (N·m)' }
    }
  };

  /**
   * 电机库示例
   *   type = 电机类型（linear / torque / coil）；axis 由类型字典派生，不手写
   *   req  = 计算必需字段（缺项即判「信息不全、无法判定」）
   */
  const motors = {
    list: [
      /* ---------------- 直线电机 ---------------- */
      {
        id: 'ILM-12-020', name: 'ILM-12-020', type: 'linear', config: '默认',
        mfr: 'Akai', series: 'ILM-12', structure: '无铁芯', cooling: '普通',
        forceCont: 12, forcePeak: 48, maxSpeedPeak: 5.0, maxSpeedCont: 6.0,
        Kf: 5.6, Ke: 3.9, Km: 2.1, R25: 8.8, L: 14.2, Te: 1.6, Pn: 32,
        Kth: 1.1, Tmax: 110, Icont: 2.14, Ipeak: 8.57, Ubus: 400,
        moverMass: 0.45, coilLen: 96, trackMass: 2.1, polePitch: 36, magForce: 0.2, mass: 0.9,
        source: { doc: 'ILM-12 系列样册', page: 3, date: '2026-11-10', method: '手工录入' }
      },
      {
        id: 'ILM-12-040', name: 'ILM-12-040', type: 'linear', config: '默认',
        mfr: 'Akai', series: 'ILM-12', structure: '无铁芯', cooling: '水冷',
        forceCont: 22, forcePeak: 88, maxSpeedPeak: 4.0, maxSpeedCont: 5.0,
        Kf: 7.9, Ke: 5.5, Km: 2.4, R25: 6.4, L: 18.5, Te: 2.9, Pn: 46,
        Kth: 1.9, Tmax: 110, Icont: 2.78, Ipeak: 11.1, Ubus: 400,
        moverMass: 0.8, coilLen: 112, trackMass: 2.6, polePitch: 42, magForce: 0.4, mass: 1.4,
        source: { doc: 'ILM-12 系列样册', page: 4, date: '2026-11-10', method: '手工录入' }
      },
      {
        id: 'IC-210-040', name: 'IC-210-040', type: 'linear', config: '默认',
        mfr: 'Akai', series: 'IC-210', structure: '有铁芯', cooling: '普通',
        forceCont: 42, forcePeak: 168, maxSpeedPeak: 2.5, maxSpeedCont: 4.0,
        Kf: 11.5, Ke: 8.0, Km: 2.92, R25: 6.2, L: 12.5, Te: 2.0, Pn: 78,
        Kth: 1.6, Tmax: 120, Icont: 3.65, Ipeak: 14.6, Ubus: 600,
        moverMass: 1.4, coilLen: 168, trackMass: 5.2, polePitch: 48, magForce: 0.9, mass: 2.8,
        source: { doc: 'IC-210 系列样册', page: 2, date: '2026-11-12', method: '手工录入' }
      },
      {
        id: 'IC-210-080', name: 'IC-210-080', type: 'linear', config: '默认',
        mfr: 'Akai', series: 'IC-210', structure: '有铁芯', cooling: '水冷',
        forceCont: 75, forcePeak: 300, maxSpeedPeak: 2.0, maxSpeedCont: 3.2,
        Kf: 15, Ke: 10, Km: 3.4, R25: 5.5, L: 15.8, Te: 2.9, Pn: 96,
        Kth: 2.2, Tmax: 120, Icont: 5.0, Ipeak: 20, Ubus: 600,
        moverMass: 2.4, coilLen: 208, trackMass: 7.4, polePitch: 48, magForce: 1.4, mass: 5.2,
        source: { doc: 'IC-210 系列样册', page: 3, date: '2026-11-12', method: '手工录入' }
      },
      {
        // 故意缺 Kth / Tmax，用于演示「信息不全、无法判定」
        id: 'ILM-20-050', name: 'ILM-20-050', type: 'linear', config: '默认',
        mfr: 'Akai', series: 'ILM-20', structure: '无铁芯', cooling: '气冷',
        forceCont: 50, forcePeak: 200, maxSpeedPeak: 4.0, maxSpeedCont: 5.5,
        Kf: 9, Ke: 6.2, Km: 2.6, R25: 5.1, L: 20, Te: 3.2, Pn: 60,
        Icont: 5.56, Ipeak: 22.2, Ubus: 500,
        moverMass: 1.1, coilLen: 148, trackMass: 3.6, polePitch: 42, magForce: 0.6, mass: 2.0,
        source: { doc: 'ILM-20 系列样册', page: 5, date: '2026-11-14', method: '手工录入' }
      },
      /* ---------------- 音圈电机 ---------------- */
      {
        // 样册格式：AVM12-6.4；无「最高速度」字段 → 留空，按信息不全处理
        id: 'AVM12-6.4 / 默认', name: 'AVM12-6.4', type: 'coil', config: '默认',
        mfr: 'Akai', series: 'AVM12', structure: '音圈', cooling: '普通',
        stroke: 6.4,
        forceCont: 0.86, forcePeak: 3.33, Kf: 0.54, Ke: 0.54, Km: 0.50,
        R25: 1.17, L: 0.10, Te: 0.09, Icont: 1.6, Ipeak: 6.2, Pn: 3.9,
        Tmax: 100, Kth: 0.051, Ubus: 60,
        moverMass: 0.005, coreMass: 7.3, gap: 0.35,
        source: { doc: 'AVM12 音圈样册', page: 1, date: '2026-11-17', method: '手工录入' }
      },
      {
        // 同系列大行程型号，用于演示行程仅展示、不参与准入
        id: 'AVM20-25 / 默认', name: 'AVM20-25', type: 'coil', config: '默认',
        mfr: 'Akai', series: 'AVM20', structure: '音圈', cooling: '普通',
        stroke: 25,
        forceCont: 2.2, forcePeak: 8.5, Kf: 1.35, Ke: 1.35, Km: 1.1,
        R25: 2.4, L: 0.6, Te: 0.25, Icont: 1.63, Ipeak: 6.3, Pn: 9.5,
        Tmax: 100, Kth: 0.12, Ubus: 80,
        moverMass: 0.018, coreMass: 15.5, gap: 0.5,
        source: { doc: 'AVM20 音圈样册', page: 1, date: '2026-11-17', method: '手工录入' }
      },
      /* ---------------- 力矩电机 ---------------- */
      {
        id: 'ADR110-B136 / 串联', name: 'ADR110-B136', type: 'torque', config: '串联',
        mfr: 'Akai', series: 'ADR110', structure: '有铁芯', cooling: '普通', bore: '标准',
        torqueCont: 4.2, torquePeak: 12.6, Kt: 1.40, Ke: 0.119, Km: 0.51,
        R25: 4.90, L: 23.50, Te: 4.80, Icont: 3.0, Ipeak: 9.0, Pn: 85.3,
        Tmax: 100, Kth: 1.1, Ubus: 600, poles: 16, maxSpeedPeak: 1000, maxSpeedCont: 2000,
        mass: 4.60, J: 4.419e-4,
        runoutAxial: 15, runoutRadial: 15, loadAxialNormal: 439, loadAxialInverted: 35,
        loadTorqueNormal: 25, loadTorqueInverted: 2.8,
        source: { doc: 'ADR110-B136 样册.pdf', page: 1, date: '2026-11-16', method: '识别草稿' }
      },
      {
        id: 'ADR110-B136 / 并联', name: 'ADR110-B136', type: 'torque', config: '并联',
        mfr: 'Akai', series: 'ADR110', structure: '有铁芯', cooling: '普通', bore: '标准',
        torqueCont: 4.2, torquePeak: 12.6, Kt: 0.70, Ke: 0.060, Km: 0.49,
        R25: 1.37, L: 6.49, Te: 4.73, Icont: 6.0, Ipeak: 18.0, Pn: 95.4,
        Tmax: 100, Kth: 1.3, Ubus: 600, poles: 16, maxSpeedPeak: 1000, maxSpeedCont: 2000,
        mass: 4.60, J: 4.419e-4,
        runoutAxial: 15, runoutRadial: 15, loadAxialNormal: 439, loadAxialInverted: 35,
        loadTorqueNormal: 25, loadTorqueInverted: 2.8,
        source: { doc: 'ADR110-B136 样册.pdf', page: 1, date: '2026-11-16', method: '识别草稿' }
      },
      {
        id: 'ADR110-B105 / 串联', name: 'ADR110-B105', type: 'torque', config: '串联',
        mfr: 'Akai', series: 'ADR110', structure: '有铁芯', cooling: '普通', bore: '标准',
        torqueCont: 3.0, torquePeak: 9.0, Kt: 1.0, Ke: 0.085, Km: 0.45,
        R25: 3.90, L: 16.2, Te: 4.15, Icont: 3.0, Ipeak: 9.0, Pn: 62,
        Tmax: 100, Kth: 1.0, Ubus: 600, poles: 16, maxSpeedPeak: 1200, maxSpeedCont: 2400,
        mass: 3.60, J: 3.07e-4,
        runoutAxial: 12, runoutRadial: 12, loadAxialNormal: 380, loadAxialInverted: 30,
        loadTorqueNormal: 20, loadTorqueInverted: 2.4,
        source: { doc: 'ADR110-B105 样册.pdf', page: 1, date: '2026-11-16', method: '识别草稿' }
      },
      {
        id: 'ADR110-B105 / 并联', name: 'ADR110-B105', type: 'torque', config: '并联',
        mfr: 'Akai', series: 'ADR110', structure: '有铁芯', cooling: '普通', bore: '标准',
        torqueCont: 3.0, torquePeak: 9.0, Kt: 0.50, Ke: 0.043, Km: 0.43,
        R25: 1.10, L: 4.2, Te: 3.82, Icont: 6.0, Ipeak: 18.0, Pn: 70,
        Tmax: 100, Kth: 1.2, Ubus: 600, poles: 16, maxSpeedPeak: 1200, maxSpeedCont: 2400,
        mass: 3.60, J: 3.07e-4,
        runoutAxial: 12, runoutRadial: 12, loadAxialNormal: 380, loadAxialInverted: 30,
        loadTorqueNormal: 20, loadTorqueInverted: 2.4,
        source: { doc: 'ADR110-B105 样册.pdf', page: 1, date: '2026-11-16', method: '识别草稿' }
      },
      {
        id: 'ADR135-B136 / 串联', name: 'ADR135-B136', type: 'torque', config: '串联',
        mfr: 'Akai', series: 'ADR135', structure: '有铁芯', cooling: '普通', bore: '特殊',
        torqueCont: 6.0, torquePeak: 18.0, Kt: 2.0, Ke: 0.170, Km: 0.63,
        R25: 7.20, L: 34.5, Te: 4.79, Icont: 3.0, Ipeak: 9.0, Pn: 118,
        Tmax: 100, Kth: 1.5, Ubus: 600, poles: 16, maxSpeedPeak: 800, maxSpeedCont: 1800,
        mass: 6.80, J: 8.90e-4,
        runoutAxial: 15, runoutRadial: 15, loadAxialNormal: 520, loadAxialInverted: 42,
        loadTorqueNormal: 35, loadTorqueInverted: 3.4,
        source: { doc: 'ADR135-B136 样册.pdf', page: 2, date: '2026-11-16', method: '识别草稿' }
      },
      {
        id: 'ADR135-B136 / 并联', name: 'ADR135-B136', type: 'torque', config: '并联',
        mfr: 'Akai', series: 'ADR135', structure: '有铁芯', cooling: '普通', bore: '特殊',
        torqueCont: 6.0, torquePeak: 18.0, Kt: 1.0, Ke: 0.085, Km: 0.60,
        R25: 1.80, L: 8.6, Te: 4.78, Icont: 6.0, Ipeak: 18.0, Pn: 132,
        Tmax: 100, Kth: 1.7, Ubus: 600, poles: 16, maxSpeedPeak: 800, maxSpeedCont: 1800,
        mass: 6.80, J: 8.90e-4,
        runoutAxial: 15, runoutRadial: 15, loadAxialNormal: 520, loadAxialInverted: 42,
        loadTorqueNormal: 35, loadTorqueInverted: 3.4,
        source: { doc: 'ADR135-B136 样册.pdf', page: 2, date: '2026-11-16', method: '识别草稿' }
      },
      {
        // 能力偏小，用于演示「不通过」标红（需求 7.2 下限为红线但不隐藏）
        id: 'ADR050-B050 / 串联', name: 'ADR050-B050', type: 'torque', config: '串联',
        mfr: 'Akai', series: 'ADR050', structure: '有铁芯', cooling: '普通', bore: '标准',
        torqueCont: 1.2, torquePeak: 3.6, Kt: 0.65, Ke: 0.055, Km: 0.35,
        R25: 5.8, L: 12.4, Te: 3.9, Icont: 1.85, Ipeak: 5.54, Pn: 34,
        Tmax: 100, Kth: 0.8, Ubus: 400, poles: 12, maxSpeedPeak: 1800, maxSpeedCont: 3600,
        mass: 1.9, J: 1.55e-4,
        runoutAxial: 10, runoutRadial: 10, loadAxialNormal: 220, loadAxialInverted: 18,
        loadTorqueNormal: 12, loadTorqueInverted: 1.4,
        source: { doc: 'ADR050-B050 样册.pdf', page: 1, date: '2026-11-16', method: '识别草稿' }
      },
      {
        id: 'DDR-080 / 普通', name: 'DDR-080', type: 'torque', config: '普通',
        mfr: 'Akai', series: 'DDR-080', structure: '无铁芯', cooling: '普通',
        torqueCont: 1.5, torquePeak: 5.0, Kt: 0.85, Ke: 0.090, Km: 0.42,
        R25: 6.4, L: 9.8, Te: 1.5, Icont: 1.76, Ipeak: 5.88, Pn: 28,
        Tmax: 100, Kth: 0.9, Ubus: 400, poles: 8, maxSpeedPeak: 1500, maxSpeedCont: 3000,
        mass: 1.60, J: 1.20e-4,
        runoutAxial: 8, runoutRadial: 8, loadAxialNormal: 180, loadAxialInverted: 15,
        loadTorqueNormal: 9, loadTorqueInverted: 1.1,
        source: { doc: 'DDR-080 样册', page: 2, date: '2026-11-15', method: '手工录入' }
      },
      {
        id: 'DDR-080 / 气冷', name: 'DDR-080', type: 'torque', config: '气冷',
        mfr: 'Akai', series: 'DDR-080', structure: '无铁芯', cooling: '气冷',
        torqueCont: 2.2, torquePeak: 5.0, Kt: 0.85, Ke: 0.090, Km: 0.42,
        R25: 6.4, L: 9.8, Te: 1.5, Icont: 2.59, Ipeak: 5.88, Pn: 32,
        Tmax: 100, Kth: 1.3, Ubus: 400, poles: 8, maxSpeedPeak: 1500, maxSpeedCont: 3000,
        mass: 1.70, J: 1.20e-4,
        runoutAxial: 8, runoutRadial: 8, loadAxialNormal: 180, loadAxialInverted: 15,
        loadTorqueNormal: 9, loadTorqueInverted: 1.1,
        source: { doc: 'DDR-080 样册', page: 3, date: '2026-11-15', method: '手工录入' }
      },
      {
        id: 'DDR-080 / 水冷', name: 'DDR-080', type: 'torque', config: '水冷',
        mfr: 'Akai', series: 'DDR-080', structure: '无铁芯', cooling: '水冷',
        torqueCont: 3.0, torquePeak: 5.0, Kt: 0.85, Ke: 0.090, Km: 0.42,
        R25: 6.4, L: 9.8, Te: 1.5, Icont: 3.53, Ipeak: 5.88, Pn: 38,
        Tmax: 100, Kth: 2.1, Ubus: 400, poles: 8, maxSpeedPeak: 1500, maxSpeedCont: 3000,
        mass: 1.85, J: 1.20e-4,
        runoutAxial: 8, runoutRadial: 8, loadAxialNormal: 180, loadAxialInverted: 15,
        loadTorqueNormal: 9, loadTorqueInverted: 1.1,
        source: { doc: 'DDR-080 样册', page: 4, date: '2026-11-15', method: '手工录入' }
      },
      {
        id: 'USR-100 / 默认', name: 'USR-100', type: 'torque', config: '默认',
        mfr: 'Akai', series: 'USR', structure: '超声波力矩电机', cooling: '普通',
        torqueCont: 2.0, torquePeak: 6.0, Kt: 0.60, Ke: 0.050, Km: 0.30,
        R25: 12.0, L: 14.0, Te: 1.2, Icont: 3.33, Ipeak: 10.0, Pn: 22,
        Tmax: 90, Kth: 1.0, Ubus: 300, poles: 2, maxSpeedPeak: 300, maxSpeedCont: 600,
        mass: 1.20, J: 1.50e-4,
        runoutAxial: 6, runoutRadial: 6, loadAxialNormal: 120, loadAxialInverted: 10,
        loadTorqueNormal: 6, loadTorqueInverted: 0.8,
        loadTorqueNormal: 6, loadTorqueInverted: 0.8,
        source: { doc: 'USR 样册', page: 1, date: '2026-11-14', method: '手工录入' }
      }
    ]
  };
  // axis 由类型字典派生，避免同一事实两处维护
  motors.list.forEach(m => { m.axis = MOTOR_TYPES[m.type].axis; });
  motors.linear = motors.list.filter(m => m.axis === 'linear');
  motors.rotary = motors.list.filter(m => m.axis === 'rotary');

  /**
   * 电机字段字典：按「电机类型」分叉，直接对照厂商样册的表格顺序
   *   req    计算必需（需求 6.2 第 1 档），缺项即判「信息不全、无法判定」
   *   factor 录入单位 → 内部单位的换算系数（如音圈线圈质量按 g 录入，内部按 kg 计算）
   */
  const motorFieldGroups = {
    linear: [
      // 必填 = 真正参与计算的 8 项（第 48 轮，逐参数删一法实测）：
      //   forceCont/forcePeak → 安全系数与两个负载系数、准入①
      //   Kf                  → 连续/峰值电流 → 线圈温度、母线电压
      //   Ke/R25/Kth/Tmax     → 母线电压与线圈温度
      //   Ubus                → 超耐压判定
      // 其余（Km/L/Te/Icont/Ipeak/Pn/电磁周期/磁吸力/线圈质量/长度/磁轨质量）不影响任何计算，为选填
      { title: '性能参数', fields: [
        { key: 'forceCont', label: '持续推力', unit: 'N', req: true },
        { key: 'forcePeak', label: '峰值推力', unit: 'N', req: true },
        { key: 'Kf', label: '力常数 ±10%', unit: 'N/Arms', req: true },
        { key: 'Ke', label: '反电势常数 ±10%', unit: 'Vpeak/(m/s)', req: true },
        { key: 'Km', label: '电机常数 @25°C', unit: 'N/SqRt(W)' },
        { key: 'R25', label: '相间电阻 @25°C ±10%', unit: 'Ω', req: true },
        { key: 'L', label: '相间电感 ±30%', unit: 'mH' },
        { key: 'Te', label: '电气时间常数', unit: 'ms' },
        // 第 49 轮：按用户要求恢复为必填（作为数据完整性要求，即便当前不进入计算）
        // 第 51 轮：`{cool}` 为冷却方式占位符，录入表单按所选冷却方式替换（普通→自冷、水冷→水冷、气冷→气冷）
        { key: 'Icont', label: '持续电流 ({cool}) @100°C', unit: 'Arms', req: true },
        { key: 'Ipeak', label: '峰值电流', unit: 'Arms', req: true },
        { key: 'Pn', label: '持续热功率 ({cool}) @100°C', unit: 'W' },
        { key: 'Tmax', label: '最高线圈温度', unit: '°C', req: true },
        { key: 'Kth', label: '热耗散常数 ({cool})', unit: 'W/°C', req: true },
        { key: 'Ubus', label: '最高母线电压', unit: 'Vdc', req: true },
        { key: 'polePitch', label: '电磁周期', unit: 'mm' },
        { key: 'magForce', label: '磁吸力', unit: 'kN' }
      ] },
      { title: '机械参数', fields: [
        { key: 'moverMass', label: '线圈质量 ({cool})', unit: 'kg' },
        { key: 'coilLen', label: '线圈长度 ({cool})', unit: 'mm' },
        { key: 'trackMass', label: '磁轨质量 (每米)', unit: 'kg/m' }
      ] },
      { title: '准入补充（样册通常不提供，留空即无法判定）', optionalAdd: true, fields: [
        { key: 'maxSpeedPeak', label: '最高速度 @峰值推力', unit: 'm/s' },
        { key: 'maxSpeedCont', label: '最高速度 @持续推力', unit: 'm/s' }
      ] }
    ],
    torque: [
      // 必填 = 真正参与计算的 10 项（第 48 轮实测）：
      //   torqueCont/torquePeak → 安全系数、两个负载系数、准入①
      //   Kt → 连续/峰值电流 → 线圈温度、母线电压
      //   Ke/R25/Kth/Tmax → 母线电压与线圈温度；Ubus → 超耐压判定
      //   maxSpeedPeak/maxSpeedCont → 准入②（力矩样册均提供，故列在此处而非「准入补充」）
      { title: '性能参数', fields: [
        { key: 'torqueCont', label: '持续转矩 @100°C', unit: 'Nm', req: true },
        { key: 'torquePeak', label: '峰值转矩', unit: 'Nm', req: true },
        { key: 'Kt', label: '转矩常数 ±10%', unit: 'Nm/Arms', req: true },
        { key: 'Ke', label: '反电势常数 ±10%', unit: 'Vpeak/rpm', req: true },
        { key: 'Km', label: '电机常数 @25°C', unit: 'Nm/SqRt(W)' },
        { key: 'R25', label: '相间电阻 @25°C ±10%', unit: 'Ω', req: true },
        { key: 'L', label: '相间电感 ±20%', unit: 'mH' },
        { key: 'Te', label: '电气时间常数', unit: 'ms' },
        // 第 49 轮：按用户要求恢复为必填
        { key: 'Icont', label: '持续电流 @100°C', unit: 'Arms', req: true },
        { key: 'Ipeak', label: '峰值电流', unit: 'Arms', req: true },
        { key: 'Pn', label: '持续热功率 @100°C', unit: 'W' },
        { key: 'Tmax', label: '最高线圈温度', unit: '°C', req: true },
        { key: 'Kth', label: '热耗散常数', unit: 'W/°C', req: true },
        { key: 'Ubus', label: '最高母线电压', unit: 'Vdc', req: true },
        { key: 'poles', label: '极数', unit: '-' },
        { key: 'maxSpeedPeak', label: '最高转速 @峰值转矩', unit: 'rpm', req: true },
        { key: 'maxSpeedCont', label: '最高转速 @持续转矩', unit: 'rpm', req: true }
      ] },
      { title: '机械参数', fields: [
        { key: 'mass', label: '总质量', unit: 'kg' },
        { key: 'J', label: '转动惯量', unit: 'kg·m²' },
        { key: 'runoutAxial', label: '轴向端跳', unit: 'μm' },
        { key: 'runoutRadial', label: '径向端跳', unit: 'μm' },
        { key: 'loadAxialNormal', label: '最大轴向载荷 (正常安装)', unit: 'N' },
        { key: 'loadAxialInverted', label: '最大轴向载荷 (倒装/侧装)', unit: 'N' },
        { key: 'loadTorqueNormal', label: '最大扭矩载荷 (正常安装)', unit: 'Nm' },
        { key: 'loadTorqueInverted', label: '最大扭矩载荷 (倒装/侧装)', unit: 'Nm' }
      ] }
    ],
    coil: [
      // 必填 = 真正参与计算的 8 项（第 48 轮实测，与直线同构）：持续/峰值推力、力常数、
      // 反电势常数、电阻、最高线圈温度、热耗散常数、最高电压。
      // 行程只作展示、不参与判定，故改为选填。
      { title: '性能参数', fields: [
        { key: 'stroke', label: '行程', unit: 'mm' },
        { key: 'forceCont', label: '持续推力 @100°C', unit: 'N', req: true },
        { key: 'forcePeak', label: '峰值推力', unit: 'N', req: true },
        { key: 'Kf', label: '力常数 ±10%', unit: 'N/A', req: true },
        { key: 'Ke', label: '反电势常数 ±10%', unit: 'V/(m/s)', req: true },
        { key: 'Km', label: '电机常数 @25°C', unit: 'N/SqRt(W)' },
        { key: 'R25', label: '电阻 @25°C ±10%', unit: 'Ω', req: true },
        { key: 'L', label: '电感 ±20%', unit: 'mH' },
        { key: 'Te', label: '电气时间常数', unit: 'ms' },
        // 第 49 轮：按用户要求恢复为必填
        { key: 'Icont', label: '持续电流 @100°C', unit: 'A', req: true },
        { key: 'Ipeak', label: '峰值电流', unit: 'A', req: true },
        { key: 'Pn', label: '持续热功率 @100°C', unit: 'W' },
        { key: 'Tmax', label: '最高线圈温度', unit: '°C', req: true },
        { key: 'Kth', label: '热耗散常数', unit: 'W/°C', req: true },
        { key: 'Ubus', label: '最高电压', unit: 'Vdc', req: true }
      ] },
      { title: '机械参数', fields: [
        // 音圈机械参数按 g 录入，统一由 factor 换算为内部单位 kg（第 63 轮 Q4 裁定）
        { key: 'moverMass', label: '线圈质量', unit: 'g', factor: 0.001 },
        { key: 'coreMass', label: '铁芯质量', unit: 'g', factor: 0.001 },
        { key: 'gap', label: '运动间隙', unit: 'mm' }
      ] },
      { title: '准入补充（样册通常不提供，留空即无法判定）', optionalAdd: true, fields: [
        { key: 'maxSpeedPeak', label: '最高速度 @峰值推力', unit: 'm/s' },
        { key: 'maxSpeedCont', label: '最高速度 @持续推力', unit: 'm/s' }
      ] }
    ]
  };


