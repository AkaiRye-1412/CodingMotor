/* 电机选型工具 — 计算内核（原型）
 * 严格按 doc/requirements.md 第 4、5、7 章实现：
 *   第 4 章  运动规划：点到点（形状可选）/ 自定义（形状自动判定）/ S 曲线 τ
 *   第 5 章  力与力矩、两阶段数据流、热模型、所需母线电压
 *   第 7 章  两条准入条件、安全余量、供电校核
 * 所有返回结构均携带「可解释字段」，界面据此展示推算依据。
 */
window.CALC = (function () {
  'use strict';

  const ALPHA_CU = 0.00393;          // 铜电阻温度系数 /K
  const CURVE_SAMPLES = 240;         // 曲线采样点数

  /* ---------- 通用工具 ---------- */
  const isNum = v => typeof v === 'number' && isFinite(v);
  const num = v => (isNum(v) ? v : null);
  const clone = o => JSON.parse(JSON.stringify(o));

  function round(x, n) {
    if (!isNum(x)) return null;
    const p = Math.pow(10, n);
    return Math.round(x * p) / p;
  }

  /* =========================================================
   * 一、运动规划
   * ========================================================= */

  /**
   * 点到点模式（需求 FR-MOT-01 / 第 4.2 节）
   *   梯形：加速 / 匀速 / 减速各占 T 的 1/3（x = T/3）
   *   三角形：无匀速段（x = T/2），此时 1:3 比例不适用
   *   T 不含停顿时间
   */
  function planPointToPoint(input) {
    const { dist, time, shape } = input;
    if (!isNum(dist) || dist <= 0) return err('距离必须为正数');
    if (!isNum(time) || time <= 0) return err('运动时间必须为正数');

    const useTriangle = shape === 'triangle';
    const x = useTriangle ? time / 2 : time / 3;

    return buildCurve({
      mode: 'p2p',
      dist,
      time,
      x,
      vPeak: dist / (time - x),
      aPeak: dist / (x * (time - x)),
      shape: useTriangle ? 'triangle' : 'trapezoid',
      note: useTriangle
        ? '三角形：匀速时间为 0，加速/减速各占 T/2（1:3 比例不适用）'
        : '梯形：加速 / 匀速 / 减速各占运动时间 1/3'
    });
  }

  /**
   * 自定义模式（FR-MOT-02 / FR-MOT-03）
   *   形状不选，由输入自动判定；缺少的参数由系统反算（第 26 轮）。
   *   优先级：加速度上限 > 速度上限 > 默认 1:3
   *     —— 同一时间内，加速度用得越足，峰值速度越低，故优先用加速度定形。
   */
  function planCustom(input) {
    const { dist } = input;
    if (!isNum(dist) || dist <= 0) return err('距离必须为正数');

    const T = isNum(input.time) && input.time > 0 ? input.time : null;
    const vLim = isNum(input.vMax) && input.vMax > 0 ? input.vMax : null;
    const aLim = isNum(input.aMax) && input.aMax > 0 ? input.aMax : null;
    const auto = { time: !T, vMax: !vLim, aMax: !aLim };

    // ---------- 情形一：已知运动时间 ----------
    if (T) {
      const aTri = 4 * dist / (T * T);
      const vTri = 2 * dist / T;
      let x = null, how = '';

      if (aLim && aLim < aTri - 1e-12) {
        return err(`最大加速度 ${fmt(aLim, 3)} 不足以在 ${fmt(T, 4)} s 内走完 ${fmt(dist, 4)}\n` +
          `所需加速度下限为 ${fmt(aTri, 3)}（三角形是加速度需求最低的形状）。\n` +
          '请提高加速度上限，或延长运动时间');
      }

      if (aLim) {
        // a·x(T−x) = S，取较小根 → 0 < x ≤ T/2
        const disc = aLim * aLim * T * T - 4 * aLim * dist;
        x = (aLim * T - Math.sqrt(Math.max(disc, 0))) / (2 * aLim);
        how = `由最大加速度 ${fmt(aLim, 3)} 反解加速时间`;
      }

      if (vLim) {
        const xv = T - dist / vLim;
        if (xv <= 0) {
          return err(`最大速度 ${fmt(vLim, 3)} 过低：仅匀速段就需 ${fmt(dist / vLim, 4)} s，\n` +
            `已超过运动时间 ${fmt(T, 4)} s。请提高最大速度或延长运动时间`);
        }
        if (x === null) {
          x = Math.min(xv, T / 2);
          how = Math.abs(xv - T / 2) < 1e-12
            ? `最大速度 ${fmt(vLim, 3)} 恰为三角形峰值 ${fmt(vTri, 3)}`
            : `由最大速度 ${fmt(vLim, 3)} 反解加速时间`;
        } else if (x > xv + 1e-12) {
          return err(`速度与加速度上限互相矛盾：\n` +
            `按加速度 ${fmt(aLim, 3)} 加速，峰值速度需 ${fmt(dist / (T - x), 3)}，超过最大速度 ${fmt(vLim, 3)}。\n` +
            '请放宽其中之一，或延长运动时间');
        }
      }

      if (x === null) {
        x = T / 3;
        how = '未给速度与加速度上限，按标准梯形默认（加/匀/减各占 1/3）';
      }

      return finalize(x, T, dist, how, auto, vLim);
    }

    // ---------- 情形二：未给运动时间 → 反算时间 ----------
    if (aLim) {
      if (vLim) {
        const xTry = vLim / aLim;
        const tTry = dist / vLim + xTry;
        if (xTry <= tTry / 2 - 1e-12) {
          // 能形成匀速平台段 → 梯形
          return finalize(xTry, tTry, dist,
            `由「最大速度 + 最大加速度」反算运动时间 ${fmt(tTry, 4)} s`,
            auto, vLim);
        }
      }
      // 三角形：x = √(S/a)，T = 2x，v = √(S·a)
      const x = Math.sqrt(dist / aLim);
      const tc = 2 * x;
      const v = aLim * x;
      if (vLim && v > vLim + 1e-12) {
        return err(`按最大加速度 ${fmt(aLim, 3)} 形成的三角形峰值速度 ${fmt(v, 3)} 超过最大速度 ${fmt(vLim, 3)}\n` +
          '请降低加速度、提高速度上限，或直接给出运动时间');
      }
      return finalize(x, tc, dist,
        `由最大加速度 ${fmt(aLim, 3)} 反算运动时间 ${fmt(tc, 4)} s（结果恰为三角形）`, auto, vLim);
    }

    if (vLim) {
      return err('只给最大速度无法确定曲线：速度只约束匀速平台段，\n' +
        '还需「最大加速度」或「运动时间」之一');
    }

    return err('缺少运动时间、最大速度与最大加速度，无法求解\n' +
      '至少补充「运动时间」，或同时给出「最大速度」与「最大加速度」');
  }

  /**
   * 由加速时间 x 组装曲线对象（含形状自动判定，第 26 轮）
   *   x = T/2 → 三角形（无匀速段）；0 < x < T/2 → 梯形
   */
  function finalize(x, T, dist, how, auto, vLim) {
    const tri = Math.abs(x - T / 2) < Math.max(1e-12, T * 1e-9);
    if (tri) x = T / 2;
    else x = Math.min(Math.max(x, 1e-12), T / 2);

    const vPeak = dist / (T - x);
    const aPeak = dist / (x * (T - x));

    // 若给了速度上限，峰值不得超限
    if (vLim && vPeak > vLim + 1e-9 * Math.max(1, vLim)) {
      return err(`解得的峰值速度 ${fmt(vPeak, 3)} 超过最大速度上限 ${fmt(vLim, 3)}，工况矛盾`);
    }

    return buildCurve({
      mode: 'custom',
      dist, time: T, x, vPeak, aPeak,
      shape: tri ? 'triangle' : 'trapezoid',
      autoShape: true,
      auto,
      timeDerived: !!(auto && auto.time),
      note: how
    });
  }

  /** 统一构造曲线对象（含 S 曲线平滑，需求 4.3） */
  function buildCurve(o) {
    const { dist, time, x } = o;
    return {
      ok: true,
      dist,
      time,
      x,
      vPeak: o.vPeak,
      aPeakIdeal: o.aPeak,
      aPeak: o.aPeak,
      shape: o.shape || 'trapezoid',
      autoShape: !!o.autoShape,
      auto: o.auto || { time: false, vMax: false, aMax: false },
      mode: o.mode,
      note: o.note,
      tau: 0,
      smoothApplied: false,
      timeDerived: !!o.timeDerived,
      // 各段时间（S 曲线关闭时）
      tAccel: x,
      tCruise: time - 2 * x,
      tDecel: x
    };
  }

  /**
   * 应用 S 曲线平滑（需求 4.3）
   *   τ = 单侧爬升时间；v_peak 不变、a_peak 上抬；可行条件 x ≥ 2τ
   *   点到点即 T ≥ 6τ
   */
  function applySmooth(curve, tauMs) {
    const tau = (num(tauMs) || 0) / 1000;
    const out = clone(curve);
    out.tau = tau;

    if (tau <= 0) {
      out.smoothApplied = false;
      out.aPeak = out.aPeakIdeal;
      return { ok: true, curve: out };
    }

    if (curve.x < 2 * tau) {
      const maxTau = curve.x / 2 * 1000;
      return {
        ok: false,
        message: `加速段时间 ${fmt(curve.x * 1000, 1)} ms 不足以容纳 ${fmt(tauMs, 1)} ms 平滑（需 ≥ ${fmt(2 * tauMs, 1)} ms）\n` +
          `当前工况允许的最大平滑时间为 ${fmt(maxTau, 1)} ms`,
        maxTau: round(maxTau, 1),
        closest: closestPreset(maxTau)
      };
    }

    out.smoothApplied = true;
    out.aPeak = out.vPeak / (curve.x - tau);     // 分母变小 → 上抬
    out.jerk = out.aPeak / tau;
    out.taRamp = tau;
    out.tAccel = curve.x;
    out.tCruise = curve.time - 2 * curve.x;
    out.tDecel = curve.x;
    return { ok: true, curve: out };
  }

  const PRESETS = [0, 4, 16, 56];
  function closestPreset(maxTau) {
    const candidates = PRESETS.filter(v => v <= maxTau && v > 0);
    return candidates.length ? candidates[candidates.length - 1] : null;
  }

  /* =========================================================
   * 二、理论结果（仅含负载，需求 5.2）
   * ========================================================= */

  /**
   * 直线：F = m·a + F_res + m·g·sinθ
   *   重力分量由带方向倾斜角 θ 的正负自动决定（90°=向上阻碍，270°=向下助动）
   * 旋转：T = J·α + T_f（无重力分量：纯回转模型无质量与质心偏距输入）
   */
  const G = 9.81;
  /**
   * 单位桥接（需求第 2 章）：
   *   旋转内部曲线以 °/s、°/s² 计算（由 角度(°)+时间(s) 推出），
   *   显示与电机比对用 rpm、rpm/s（1 rpm = 6 °/s），
   *   力矩计算需 rad/s²（1° = π/180 rad）。
   */
  const DEG_TO_RAD = Math.PI / 180;
  const DISP = { linear: { v: 1, a: 1 }, rotary: { v: 1 / 6, a: 1 / 6 } };
  const accelFactor = kind => (kind === 'rotary' ? DEG_TO_RAD : 1);
  const speedDisplay = (kind, v) => v * DISP[kind].v;
  const accelDisplay = (kind, a) => a * DISP[kind].a;

  /**
   * 重力分量（仅直线）：m·g·sinθ
   *   θ 为带方向的倾斜角：90° = 垂直向上（重力阻碍，+G）；
   *   270° = 垂直向下（重力助动，−G）；其余角度按 sinθ 自动带符号。
   */
  function gravityTerm(kind, load, env) {
    if (kind !== 'linear') return 0;
    const tilt = num(env && env.tilt) || 0;
    return load * G * Math.sin(tilt * DEG_TO_RAD);
  }

  function theoretical(kind, load, curve, resist, env) {
    // 直线：m·a(N)；旋转：J·α，α 由 rpm/s 换算为 rad/s²
    const accelTerm = load * curve.aPeak * accelFactor(kind);
    const grav = gravityTerm(kind, load, env);

    // 三段推力需求（重力在加速段同样存在）
    const peak = accelTerm + resist + grav;                    // 加速段
    const cruise = Math.abs(resist + grav);                    // 匀速段：仅克服阻力 + 重力
    const decel = Math.abs(accelTerm - resist - grav);         // 减速段反向

    const dwell = (num(env && env.dwell) || 0);
    const rms = rmsOfCycle(peak, curve.tAccel, cruise, curve.tCruise, decel, curve.tDecel, dwell);

    return {
      peak: peak,
      cruise: cruise,
      decel: decel,
      accelTerm: accelTerm,
      gravity: grav,
      cont: rms,
      contMethod: 'rms',
      cycleTime: curve.time + dwell,
      dwell: dwell,
      resist: resist
    };
  }

  /** 循环 RMS：√(Σ(F²·t) / Σt) */
  function rmsOfCycle(fAccel, tAccel, fCruise, tCruise, fDecel, tDecel, dwell) {
    const total = tAccel + tCruise + tDecel + dwell;
    if (total <= 0) return 0;
    const sum = fAccel * fAccel * tAccel +
      fCruise * fCruise * tCruise +
      fDecel * fDecel * tDecel +
      0 * dwell;                                    // 停顿段出力为 0
    return Math.sqrt(Math.max(sum, 0) / total);
  }

  /* =========================================================
   * 三、准入判定（需求 7.1）
   * ========================================================= */

  /**
   * ① 连续 & 峰值 ≥ 理论需求 × (1 + 下限%)
   * ② v_peak ≤ 最高速度 @峰值 且 ≤ 最高速度 @持续
   */
  function evaluateMotor(kind, motor, theo, curve, marginLow) {
    const r = marginLow;                            // 如 0.3
    const contKey = kind === 'linear' ? 'forceCont' : 'torqueCont';
    const peakKey = kind === 'linear' ? 'forcePeak' : 'torquePeak';

    const missing = requiredMissing(motor);
    if (missing.length) {
      return {
        motor, verdict: 'unknown', pass: false,
        missing: missing.map(m => m.label),
        reasons: [`信息不全，无法判定：缺 ${missing.map(m => m.label).join('、')}`]
      };
    }

    const contNeed = theo.cont * (1 + r);
    const peakNeed = theo.peak * (1 + r);
    const contHave = motor[contKey];
    const peakHave = motor[peakKey];

    // 速度比对需换算到电机库单位：旋转 rpm，直线 m/s（需求第 2 章）
    const vDisp = speedDisplay(kind, curve.vPeak);
    const vUnit = kind === 'linear' ? 'm/s' : 'rpm';

    const passCont = contHave >= contNeed;
    const passPeak = peakHave >= peakNeed;
    // 最高速度是可选的（样册普遍不提供）：未录入则跳过该条准入，
    // 不阻断推荐；录入了才参与比较（第 43 轮）
    const hasSpeedPeak = isNum(motor.maxSpeedPeak);
    const hasSpeedCont = isNum(motor.maxSpeedCont);
    const passSpeedPeak = hasSpeedPeak ? vDisp <= motor.maxSpeedPeak : true;
    const passSpeedCont = hasSpeedCont ? vDisp <= motor.maxSpeedCont : true;
    const speedSkipped = [];
    if (!hasSpeedPeak) speedSkipped.push('最高速度 @峰值');
    if (!hasSpeedCont) speedSkipped.push('最高速度 @持续');

    const reasons = [];
    if (!passCont) reasons.push(`连续能力 ${icon(false)} ${fmt(contHave, 2)} < 需求 ${fmt(contNeed, 2)}（含下限 ${r * 100}%）`);
    else reasons.push(`连续能力 ${icon(true)} ${fmt(contHave, 2)} ≥ 需求 ${fmt(contNeed, 2)}`);
    if (!passPeak) reasons.push(`峰值能力 ${icon(false)} ${fmt(peakHave, 2)} < 需求 ${fmt(peakNeed, 2)}`);
    else reasons.push(`峰值能力 ${icon(true)} ${fmt(peakHave, 2)} ≥ 需求 ${fmt(peakNeed, 2)}`);
    if (!hasSpeedPeak) {
      reasons.push(`峰值速度 ${fmt(vDisp, 2)} ${vUnit} 未校核（未录入最高速度 @峰值）`);
    } else if (!passSpeedPeak) {
      reasons.push(`峰值速度 ${icon(false)} ${fmt(vDisp, 2)} ${vUnit} > 电机 @峰值上限 ${fmt(motor.maxSpeedPeak, 2)}`);
    } else {
      reasons.push(`峰值速度 ${icon(true)} ${fmt(vDisp, 2)} ${vUnit} ≤ @峰值上限 ${fmt(motor.maxSpeedPeak, 2)}`);
    }
    if (!hasSpeedCont) {
      reasons.push(`峰值速度未校核（未录入最高速度 @持续）`);
    } else if (!passSpeedCont) {
      reasons.push(`峰值速度 ${icon(false)} ${fmt(vDisp, 2)} ${vUnit} > 电机 @持续上限 ${fmt(motor.maxSpeedCont, 2)}`);
    } else {
      reasons.push(`峰值速度 ${icon(true)} ${fmt(vDisp, 2)} ${vUnit} ≤ @持续上限 ${fmt(motor.maxSpeedCont, 2)}`);
    }

    const pass = passCont && passPeak && passSpeedPeak && passSpeedCont;

    // 余量（需求 7.2）：连续侧余量 = 电机连续能力 / 理论连续需求 - 1
    const headroom = theo.cont > 0 ? (contHave / (theo.cont * (1 + r))) - 1 : 0;
    const overSize = theo.cont > 0 ? (contHave / theo.cont) - 1 : 0;

    return {
      motor, pass, verdict: pass ? 'pass' : 'fail',
      passCont, passPeak, passSpeedPeak, passSpeedCont,
      speedSkipped,
      reasons,
      headroom, overSize,
      contHave, peakHave, contNeed, peakNeed
    };
  }

  /**
   * 计算必需字段是否齐备（需求 6.2 第 1 档）
   *   字段清单直接取录入模板（data.js: motorFieldGroups）的 req 标记，
   *   与「保存入库」的必填校验同源：**能入库的电机一定能参与判定**（第 43 轮）。
   *
   *   「准入补充」分组（直线/音圈的最高速度）不在此列：样册普遍不提供，
   *   留空时只是**跳过该条准入**（见 evaluateMotor），不会整条判为信息不全。
   */
  function requiredMissing(motor) {
    const type = motor.type || (has(motor, 'torqueCont') ? 'torque' : 'linear');
    const groups = (window.APP_DATA && window.APP_DATA.motorFieldGroups[type]) || [];
    return groups
      .flatMap(g => g.fields)
      .filter(f => f.req && !isNum(motor[f.key]))
      .map(f => ({ key: f.key, label: f.label }));
  }

  const has = (o, k) => o[k] != null;

  const icon = b => (b ? '✓' : '✗');

  /* =========================================================
   * 四、实际应用值（需求 5.2 / 5.3 / 5.4）
   * ========================================================= */

  /**
   * 选中电机后的重算：含电机惯量/质量、热校核、所需母线电压
   */
  function appliedValue(kind, motor, theo, curve, env) {
    const r = env.marginLow;
    const tAmb = num(env.tAmbient) != null ? env.tAmbient : 22;

    // 1) 实际应用值重算（第 45 轮口径：与参考软件一致，不叠加电机自身质量/惯量）
    //    用户填写的「总移动质量 / 负载惯量」即计算口径，电机线圈质量/转动惯量只作参数展示。
    //    这样输入的负载与算出的电流、温度、母线电压一一对应，可复算、可对照。
    const totalInertia = env.load;
    const unitCurrent = kind === 'linear' ? motor.Kf : motor.Kt;
    const accelTerm = totalInertia * curve.aPeak * accelFactor(kind);
    const grav = gravityTerm(kind, totalInertia, env);
    const peakApplied = accelTerm + env.resist + grav;
    const cruiseApplied = Math.abs(env.resist + grav);
    const decelApplied = Math.abs(accelTerm - env.resist - grav);
    const contApplied = rmsOfCycle(peakApplied, curve.tAccel, cruiseApplied, curve.tCruise,
      decelApplied, curve.tDecel, env.dwell);

    // 2) 电流
    const iPeakRms = unitCurrent ? (peakApplied / unitCurrent) : null;
    const iContRms = unitCurrent ? (contApplied / unitCurrent) : null;
    const iPeakMax = isNum(iPeakRms) ? iPeakRms * Math.SQRT2 : null;

    // 3) 热校核（需求 5.3）
    const Tmax = motor.Tmax;
    const R25 = motor.R25;
    const Kth = motor.Kth;
    let rHot = isNum(R25) && isNum(Tmax) ? R25 * (1 + ALPHA_CU * (Tmax - 25)) : null;
    let iAllow = null;
    if (isNum(rHot) && isNum(Kth) && isNum(Tmax)) {
      iAllow = Math.sqrt(Math.max(Kth * (Tmax - tAmb), 0) / (1.5 * rHot));
    }
    let tCoil = null;
    if (isNum(rHot) && isNum(Kth) && isNum(iContRms)) {
      // 一次迭代收敛
      let temp = tAmb + 1.5 * iContRms * iContRms * R25 / Kth;
      temp = tAmb + 1.5 * iContRms * iContRms * (R25 * (1 + ALPHA_CU * (temp - 25))) / Kth;
      tCoil = temp;
    }
    const thermalPass = isNum(iAllow) && isNum(iContRms) ? iContRms <= iAllow : null;

    // 4) 所需电压（第 45 轮口径，对齐参考软件）
    //    U = Ke·v + I_pk·R_hot，取「加速终点」与「匀速段」两个工作点的较大者。
    //    报出的是**相电压峰值**，不做 √3 折算（原需求 5.4 的 SVPWM 口径作废）。
    //    - I_pk 用真实峰值电流 √2·I_rms：母线电压的约束意义就是要能逼出峰值电流；
    //    - 电阻用**线圈温度下的热态值**（R25 冷态会低估压降；绕组热起来电阻更大）。
    let uReq = null, uAtAccel = null, uAtCruise = null, rVolt = null;
    // 缺哪个参数就报哪个，避免界面只显示「—」让人分不清是缺数据还是算错（第 46 轮）
    const uMissing = [];
    if (!isNum(motor.Ke)) uMissing.push('反电动势常数 Ke');
    if (!isNum(motor.R25)) uMissing.push('相间电阻 R25');
    if (!isNum(unitCurrent)) uMissing.push(kind === 'linear' ? '力常数 Kf' : '转矩常数 Kt');
    if (!uMissing.length) {
      // 优先用已算出的线圈温度；热校核数据不足时退回 R25
      const tRef = isNum(tCoil) ? tCoil : 25;
      rVolt = motor.R25 * (1 + ALPHA_CU * (tRef - 25));
      const vMotor = speedDisplay(kind, curve.vPeak);  // Ke 的基准单位：m/s 或 rpm
      uAtAccel = motor.Ke * vMotor + iPeakMax * rVolt;      // 加速终点：峰值电流
      const iCruise = env.resist / unitCurrent;
      uAtCruise = motor.Ke * vMotor + iCruise * rVolt;      // 匀速段：巡航电流
      uReq = Math.max(uAtAccel, uAtCruise);
    }

    // 5) 安全系数与负载系数（第 45 轮，对齐参考软件口径）
    //    安全系数       = (电机持续能力 − 实际持续需求) / 实际持续需求 × 100  → 余量百分比
    //    持续/峰值负载系数 = 实际需求 / 电机能力 × 100                          → 占用百分比
    const contHave = kind === 'linear' ? motor.forceCont : motor.torqueCont;
    const peakHave = kind === 'linear' ? motor.forcePeak : motor.torquePeak;
    const safety = contApplied > 0 ? ((contHave - contApplied) / contApplied) * 100 : null;
    const loadCont = isNum(contHave) && contHave > 0 ? (contApplied / contHave) * 100 : null;
    const loadPeak = isNum(peakHave) && peakHave > 0 ? (peakApplied / peakHave) * 100 : null;

    // 6) 供电结论（需求 7.4）
    let supply = null;
    if (isNum(env.uSupply) && isNum(uReq)) {
      const remain = env.uSupply - uReq;
      supply = {
        have: env.uSupply, need: uReq, remain: remain,
        rate: uReq > 0 ? (remain / uReq) * 100 : 0,
        pass: remain >= 0,
        text: remain >= 0 ? `供电满足（余量 ${fmt(remain, 1)} VDC）` : `供电不足（缺 ${fmt(-remain, 1)} VDC）`
      };
    }
    const overUbus = isNum(motor.Ubus) && isNum(uReq) ? uReq > motor.Ubus : null;

    return {
      totalInertia,
      peakApplied, cruiseApplied, decelApplied, gravity: grav,
      contApplied,
      iPeakRms, iContRms, iPeakMax,
      rHot, iAllow, tCoil, thermalPass,
      uReq, uAtAccel, uAtCruise, rVolt, uMissing,
      safety, loadCont, loadPeak, supply, overUbus,
      verdict: thermalPass === false ? 'fail' : (thermalPass === true ? 'pass' : 'unknown')
    };
  }

  /* =========================================================
   * 五、曲线采样（供图表绘制）
   * ========================================================= */

  /**
   * 生成速度-时间 与 力-时间 采样序列
   * S 曲线开启时，加速段两端各 tau 为 jerk 斜坡
   */
  function sampleCurve(curve, forceValue) {
    const N = CURVE_SAMPLES;
    const T = curve.time;
    const x = curve.x;
    const v = curve.vPeak;
    const a = curve.aPeak;
    const tau = curve.tau || 0;
    const vPts = [], fPts = [];

    const sAccel0 = 0, sAccel1 = x, sCruise1 = T - x, sDecel1 = T;

    for (let i = 0; i <= N; i++) {
      const t = (T * i) / N;
      let vel = 0, acc = 0;

      if (t <= sAccel1) {
        // 加速段
        if (tau > 0 && t < tau) {
          // jerk 上升
          acc = (a / tau) * t;
          vel = 0.5 * (a / tau) * t * t;
        } else if (tau > 0 && t < x - tau) {
          acc = a;
          vel = 0.5 * a * tau + a * (t - tau);
        } else if (tau > 0) {
          const d = t - (x - tau);
          acc = a - (a / tau) * d;
          const vAtRampStart = 0.5 * a * tau + a * (x - 2 * tau);
          vel = vAtRampStart + a * d - 0.5 * (a / tau) * d * d;
        } else {
          acc = a;
          vel = a * t;
        }
        if (vel > v) vel = v;
      } else if (t <= sCruise1) {
        vel = v; acc = 0;
      } else {
        // 减速段（镜像）
        const dt = t - sCruise1;                 // 0..x
        if (tau > 0 && dt < x - tau) {
          if (dt < tau) {
            acc = -(a / tau) * dt;
            vel = v - 0.5 * (a / tau) * dt * dt;
          } else {
            acc = -a;
            vel = v - 0.5 * a * tau - a * (dt - tau);
          }
        } else if (tau > 0) {
          const d = dt - (x - tau);
          acc = -a + (a / tau) * d;
          const vAtRampStart = v - 0.5 * a * tau - a * (x - 2 * tau);
          vel = vAtRampStart - a * d + 0.5 * (a / tau) * d * d;
        } else {
          acc = -a;
          vel = v - a * dt;
        }
        if (vel < 0) vel = 0;
      }

      vPts.push({ t, v: vel, a: acc });
      fPts.push({ t, f: forceValue(acc, vel) });
    }
    return { vPts, fPts };
  }

  function fmt(x, n) {
    if (!isNum(x)) return '—';
    if (x !== 0 && Math.abs(x) < 1e-3) return x.toExponential(2);
    const s = x.toFixed(n == null ? 3 : n);
    return s.indexOf('.') >= 0 ? s.replace(/0+$/, '').replace(/\.$/, '') : s;
  }

  /**
   * 点到点红线校核（需求 4.2：a_max / v_max 仅作红线，超出即不放行）
   *   返回 null 表示通过，否则返回错误描述
   */
  function redline(kind, curve, vMax, aMax) {
    const problems = [];
    const vDisp = speedDisplay(kind, curve.vPeak);
    const aDisp = accelDisplay(kind, curve.aPeak);
    const vUnit = kind === 'linear' ? 'm/s' : 'rpm';
    const aUnit = kind === 'linear' ? 'm/s²' : 'rpm/s';

    if (isNum(vMax) && vMax > 0 && vDisp > vMax) {
      problems.push(`峰值速度 ${fmt(vDisp, 3)} ${vUnit} 超出上限 ${fmt(vMax, 3)}（${fmt((vDisp / vMax - 1) * 100, 1)}%）`);
    }
    if (isNum(aMax) && aMax > 0 && aDisp > aMax) {
      problems.push(`峰值加速度 ${fmt(aDisp, 3)} ${aUnit} 超出上限 ${fmt(aMax, 3)}（${fmt((aDisp / aMax - 1) * 100, 1)}%）`);
    }
    if (!problems.length) return null;
    return problems.join('\n') + '\n可延长运动时间，或改用自定义模式重新分配加速度。';
  }

  function err(message) { return { ok: false, message }; }

  return {
    planPointToPoint, planCustom, applySmooth, theoretical, evaluateMotor,
    appliedValue, sampleCurve, requiredMissing, fmt, round, PRESETS,
    ALPHA_CU, closestPreset, speedDisplay, accelDisplay, accelFactor, rmsOfCycle, redline
  };
})();
