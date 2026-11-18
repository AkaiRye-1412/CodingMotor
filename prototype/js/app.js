/* 电机选型工具 — 原型交互逻辑
 * 仅实现交互设计（无服务器逻辑），所有计算走 js/calc.js。
 */
(function () {
  'use strict';

  const D = window.APP_DATA;
  const C = window.CALC;
  const fmt = C.fmt;

  /* ---------------- 状态 ---------------- */
  const state = {
    kind: 'linear',
    mode: 'p2p',
    smoothOn: false,
    tau: 16,
    marginLow: 30,
    marginHigh: 300,
    marginHighOn: false,
    pickView: 'cand',            // 右侧模块视图：cand=推荐电机 / motor=选入电机数据
    // 第 58 轮：去掉 bore —— 直线页无中孔取值、旋转页的中孔 chip 已移除，该维度不再参与筛选
    filters: { structure: new Set(), cooling: new Set() },
    // 一份电机库，型号自带 type 与 axis；页面只筛出本页可计算的类型
    motors: D.motors.list.slice(),
    // 每个 kind 维护自己的方案列表与当前索引
    books: {
      linear: { curves: [defaultCurve('linear')], index: 0 },
      rotary: { curves: [defaultCurve('rotary')], index: 0 }
    }
  };

  function defaultCurve(kind) {
    const base = {
      shape: 'trapezoid',          // 仅点到点使用；自定义模式的形状由求解器自动判定
      dist: 0, time: 0, vMax: null, aMax: null, dwell: 0, resist: 0,
      tAmbient: 22, uSupply: 311, tilt: 0, direction: 'up', motorId: null
    };
    // 倾斜角默认 0°（水平，无重力分量），与参考软件一致（第 45 轮）；
    // 需要垂直工况时由用户手改为 90°（向上）/ 270°（向下）
    return kind === 'linear'
      ? Object.assign(base, { load: 0, tilt: 0 })
      : Object.assign(base, { load: 0 });
  }

  const book = () => state.books[state.kind];
  const curve = () => book().curves[book().index];
  const schema = () => D.schema[state.kind];

  /* ---------------- 计算管线 ---------------- */
  const UNIT = {
    linear: { v: 1, a: 1 },
    rotary: { v: 6, a: 6 }        // 输入 rpm / rpm·s⁻¹ → 内部 °/s、°/s²
  };

  /**
   * 计算一条方案
   *   cvOverride 缺省为当前曲线；导出报告需要逐条方案计算时传入各条曲线（第 42 轮）
   *   运动模式 / S 曲线属于页签级设置，不随曲线变化
   */
  function compute(cvOverride) {
    const kind = state.kind;
    const cv = cvOverride || curve();
    const u = UNIT[kind];

    // 1) 规划曲线
    let planned;
    if (state.mode === 'p2p') {
      planned = C.planPointToPoint({ dist: cv.dist, time: cv.time, shape: cv.shape });
    } else {
      planned = C.planCustom({
        dist: cv.dist,
        time: cv.time,
        vMax: isNum(cv.vMax) ? cv.vMax * u.v : null,
        aMax: isNum(cv.aMax) ? cv.aMax * u.a : null,
        shape: cv.shape
      });
    }
    if (!planned.ok) return { stage: 'plan', error: planned.message };

    // 2) S 曲线平滑
    //    点到点模式下 v_max / a_max 已改为理论推算值（第 25 轮），红线校核随之取消；
    //    电机能力上限交由准入条件②兜底。
    let smooth;
    if (state.smoothOn) {
      smooth = C.applySmooth(planned, state.tau);
      if (!smooth.ok) {
        return {
          stage: 'smooth', error: smooth.message,
          maxTau: smooth.maxTau, closest: smooth.closest, planned
        };
      }
      planned = smooth.curve;
    }

    // 3) 理论结果（倾斜角与方向参与重力分量计算，仅直线）
    const envBase = {
      dwell: cv.dwell, tilt: isNum(cv.tilt) ? cv.tilt : 0, direction: cv.direction
    };
    const theo = C.theoretical(kind, cv.load, planned, cv.resist || 0, envBase);

    // 5) 候选判定
    const marginLow = state.marginLow / 100;
    // 候选列表混排三种类型（第 38 轮），但只有与当前页签同量纲的类型参与判定：
    // 力矩电机的 N·m 无法与直线页的 N 比较，跨量纲行标「不适用」而不是编造 Safety
    const evals = state.motors
      .filter(m => passFilter(m))
      .map(m => (m.axis === kind
        ? C.evaluateMotor(kind, m, theo, planned, marginLow)
        : {
            motor: m, verdict: 'na', pass: false, headroom: null,
            reasons: [`${D.MOTOR_TYPES[m.type].label}电机按 ${D.MOTOR_TYPES[m.type].axis === 'linear' ? '推力 (N)' : '力矩 (N·m)'} 计算，与当前页签不同量纲，不参与判定`]
          }));

    evals.sort((a, b) => {
      if (a.verdict === 'na' || b.verdict === 'na') {
        if (a.verdict === 'na' && b.verdict === 'na') return 0;
        return a.verdict === 'na' ? 1 : -1;
      }
      if (a.pass !== b.pass) return a.pass ? -1 : 1;
      const ah = a.headroom == null ? Infinity : a.headroom;
      const bh = b.headroom == null ? Infinity : b.headroom;
      return ah - bh;
    });

    // 6) 实际应用值
    const selected = pool().find(m => m.id === cv.motorId) || null;
    const applied = selected
      ? C.appliedValue(kind, selected, theo, planned, Object.assign({}, envBase, {
          load: cv.load, resist: cv.resist || 0,
          tAmbient: cv.tAmbient, uSupply: isNum(cv.uSupply) ? cv.uSupply : null,
          marginLow
        }))
      : null;

    return { stage: 'ok', curve: planned, theo, evals, applied, selected, marginLow };
  }

  const isNum = v => typeof v === 'number' && isFinite(v);
  const numOrNull = v => (v === '' || v == null || isNaN(Number(v)) ? null : Number(v));

  /**
   * 结果展示统一保留 1 位小数（理论计算结果、电机实际应用值共用）
   *   四舍五入后若变成 0.0 而真值不为 0（极小推力/电流），退回 3 位小数，
   *   避免真值被显示成「0.0」
   */
  function one(x) {
    if (!isNum(x)) return '—';
    const s = x.toFixed(1);
    return Number(s) === 0 && x !== 0 ? fmt(x, 3) : s;
  }

  /** 当前页签可参与计算的电机（音圈是直线运动 → 归直线页） */
  const pool = () => state.motors.filter(m => m.axis === state.kind);

  /** 显示名：型号 + 配置；「默认」不显示（需求 6.1：单列样册配置取默认值） */
  function motorLabel(m) {
    const base = m.name || m.id;
    return m.config && m.config !== '默认' ? `${base} / ${m.config}` : base;
  }

  function passFilter(m) {
    const f = state.filters;
    if (f.structure.size && !f.structure.has(m.structure)) return false;
    if (f.cooling.size && !f.cooling.has(m.cooling)) return false;
    return true;
  }

  /* ---------------- 渲染：运动模式 ---------------- */
  function renderMode() {
    const cv = curve();
    const el = document.getElementById('modeBody');
    el.innerHTML = `
      <div class="seg">
        <label><input type="radio" name="mode" value="p2p" ${state.mode === 'p2p' ? 'checked' : ''}> 点到点</label>
        <label><input type="radio" name="mode" value="custom" ${state.mode === 'custom' ? 'checked' : ''}> 自定义</label>
      </div>
      ${state.mode === 'p2p' ? `
      <div class="inline" style="margin-top:1px">
        <span style="color:#3a4551">曲线形状</span>
        <span class="chip ${cv.shape === 'trapezoid' ? 'active' : ''}" data-shape="trapezoid">⏢ 梯形</span>
        <span class="chip ${cv.shape === 'triangle' ? 'active' : ''}" data-shape="triangle">▲ 三角形</span>
      </div>` : `
      <div class="inline" style="margin-top:1px" id="shapeAutoRow">
        <span style="color:#3a4551">曲线形状</span>
        <span class="shape-chip" id="shapeAutoChip">—</span>
        <span class="sub-label" style="margin:0">按输入自动判定</span>
      </div>`}

      <div class="inline" style="margin-top:1px">
        <label style="display:flex;gap:4px;align-items:center;cursor:pointer">
          <input type="checkbox" id="chkSmooth" ${state.smoothOn ? 'checked' : ''}> S 曲线
        </label>
        <label style="display:flex;gap:4px;align-items:center;color:#6b7683;margin-left:6px">
          平滑时间
          <input type="number" id="tauCustom" min="0" step="any" style="width:64px;padding:2px 4px"
            value="${state.tau}" ${state.smoothOn ? '' : 'disabled'} title="开启 S 曲线后可自由设置">
          ms
        </label>
      </div>
    `;

    // 形状选择（点到点与自定义均生效）
    el.querySelectorAll('[data-shape]').forEach(c => {
      c.addEventListener('click', () => {
        curve().shape = c.dataset.shape;
        render();
      });
    });

    el.querySelectorAll('input[name=mode]').forEach(r => {
      r.addEventListener('change', e => {
        state.mode = e.target.value;
        render();
      });
    });
    document.getElementById('chkSmooth').addEventListener('change', e => {
      state.smoothOn = e.target.checked;
      if (state.smoothOn && state.tau <= 0) state.tau = 16;   // 默认 16 ms
      render();
    });
    const tc = document.getElementById('tauCustom');
    if (tc) {
      tc.addEventListener('input', e => {
        const v = numOrNull(e.target.value);
        if (v == null || v < 0) return;
        state.tau = v;
        renderComputed();
      });
    }
  }

  /* ---------------- 渲染：曲线管理（项目→轴→方案） ---------------- */
  function renderCurveBar() {
    const b = book();
    const el = document.getElementById('curveBody');
    el.innerHTML = `
      <div class="curve-bar">
        <button class="btn" id="cvPrev">◀</button>
        <span class="curve-pos">曲线 ${b.index + 1} / ${b.curves.length}</span>
        <button class="btn" id="cvNext">▶</button>
        <button class="btn" id="cvNew">新建曲线</button>
        <button class="btn" id="cvDel">删除所选曲线</button>
      </div>
    `;

    const go = i => { b.index = Math.max(0, Math.min(b.curves.length - 1, i)); render(); };
    document.getElementById('cvPrev').onclick = () => go(b.index - 1);
    document.getElementById('cvNext').onclick = () => go(b.index + 1);
    document.getElementById('cvNew').onclick = () => {
      b.curves.push(defaultCurve(state.kind));
      b.index = b.curves.length - 1;
      render(); toast('已新建方案');
    };
    document.getElementById('cvDel').onclick = () => {
      if (b.curves.length === 1) { b.curves = [defaultCurve(state.kind)]; b.index = 0; }
      else { b.curves.splice(b.index, 1); b.index = Math.min(b.index, b.curves.length - 1); }
      render(); toast('已删除所选曲线');
    };
  }

  /* ---------------- 渲染：用户输入（左：输入 / 右：计算结果） ---------------- */
  function renderInputs(cmp) {
    const s = schema();
    const cv = curve();
    const el = document.getElementById('inputBody');
    const isP2P = state.mode === 'p2p';

    const solved = cmp && cmp.stage === 'ok' ? cmp.curve : null;
    const aCalc = solved ? C.accelDisplay(state.kind, solved.aPeak) : null;
    const vCalc = solved ? C.speedDisplay(state.kind, solved.vPeak) : null;
    const tCalc = solved ? solved.time : null;
    const rows = [];

    rows.push(`<div class="pair-head">
      <span></span><span>用户输入</span><span>计算结果</span><span></span>
    </div>`);

    /**
     * 双列行：左侧用户输入，右侧「实际参与计算的值」
     *   key     状态字段名；resKey 右侧结果单元格 id 后缀
     *   userVal 用户填写的值（未填为 null）
     *   effVal  实际生效值；null 表示本行无计算输出
     *   over    用户输入与生效值不一致 → 左侧标黄并给出提示
     */
    function pairRow(o) {
      const { key, resKey, label, userVal, effVal, unit, badge, hint, readOnly, derived, min } = o;
      const filled = isNum(userVal) && userVal > 0;
      const b = badge === 'req' ? '<span class="req-star">*</span>'
        : badge === 'opt' ? '<span class="badge opt">可选</span>' : '';

      // 输入被计算结果取代 → 左侧标黄
      let over = false;
      if (derived && filled && isNum(effVal)) {
        over = Math.abs(userVal - effVal) > Math.max(Math.abs(effVal) * 0.005, 1e-6);
      }

      const left = readOnly
        ? `<div class="cell-result blank" style="justify-content:center">—</div>`
        : `<input type="number" step="any" id="f_${key}" data-auto="${filled ? 0 : 1}"
             ${min != null ? `min="${min}"` : ''}
             class="${over ? 'over' : ''}" placeholder="留空自动"
             value="${userVal == null ? '' : userVal}">`;

      // 无论当前有无取值都必须带 id：首次渲染时距离/时间为空、算不出结果，
      // 此时若省略 id，之后 syncCalcFields 就再也找不到这一格，理论值会永远停在「—」。
      // 只有「系统算出」的行才用紫色；直接透传的行保持中性
      const isCalc = derived && !filled;
      const cls = 'cell-result' + (isCalc ? ' calc' : '') + (effVal == null ? ' blank' : '');
      const txt = effVal == null ? '—' : fmt(effVal, 4);
      const right = `<div class="${cls}" id="r_${resKey || key}">${txt}</div>`;

      const note = over
        ? `输入的 ${fmt(userVal, 4)} 未作为取值；实际生效 ${fmt(effVal, 4)}`
        : hint;

      return `<div class="row pair">
        <label>${label} ${b}</label>
        <div class="field-wrap">${left}</div>
        ${right}
        <span class="unit">${unit}</span>
      </div>${note ? `<div class="sub-label">${note}</div>` : ''}`;
    }

    // 负载：直接参与计算，生效值即输入
    //   负质量/负惯量没有物理意义，故左侧输入 box 用 min=0 卡住
    rows.push(pairRow({
      key: 'load', label: s.loadLabel, userVal: cv.load, effVal: cv.load,
      unit: s.loadUnit, badge: 'req', min: 0
    }));

    rows.push(pairRow({
      key: 'dist', label: s.distLabel, userVal: cv.dist, effVal: cv.dist,
      unit: s.distUnit, badge: 'req', min: 0
    }));

    // 运动时间：点到点必填（右侧显示其实际取值）；自定义可留空由系统反算
    rows.push(pairRow({
      key: 'time', label: '运动时间', userVal: cv.time, effVal: tCalc,
      unit: 's', badge: isP2P ? 'req' : 'opt', derived: !isP2P, min: 0
    }));

    // 最大加速度：点到点只读（系统算出）；自定义可填、可留空
    rows.push(pairRow({
      key: isP2P ? 'aCalc' : 'aMax', label: s.aLabel,
      userVal: isP2P ? null : cv.aMax, effVal: aCalc,
      unit: s.aUnit, badge: isP2P ? '' : 'opt',
      readOnly: isP2P, derived: true, min: 0
    }));

    rows.push(pairRow({
      key: 'dwell', label: '停顿时间', userVal: cv.dwell, effVal: cv.dwell,
      unit: 's', badge: 'opt', min: 0
    }));

    // 最大速度：点到点只读；自定义可填、可留空
    rows.push(pairRow({
      key: isP2P ? 'vCalc' : 'vMax', label: s.vLabel,
      userVal: isP2P ? null : cv.vMax, effVal: vCalc,
      unit: s.vUnit, badge: isP2P ? '' : 'opt',
      readOnly: isP2P, derived: true, min: 0
    }));

    // 阻力 / 摩擦力矩（第 62 轮）：
    //   直线页「阻力」不受限——下行工况可为负（重力助动）；
    //   旋转页「摩擦力矩」恒阻碍运动，下限 0，默认 0 N·m（不填即 0，仍可正常求解）
    rows.push(pairRow({
      key: 'resist', label: s.resistLabel, userVal: cv.resist, effVal: cv.resist,
      unit: s.resistUnit, badge: 'opt',
      min: s.hasDirection ? null : 0
    }));

    if (s.hasDirection) {
      rows.push(`<div class="row"><label>方向</label>
        <div class="field-wrap">
          <label><input type="radio" name="dir" value="up" ${cv.direction === 'up' ? 'checked' : ''}> 上</label>
          <label><input type="radio" name="dir" value="down" ${cv.direction === 'down' ? 'checked' : ''}> 下</label>
        </div><span class="unit"></span></div>`);
    }

    // 倾斜角：直线页参与计算（重力分量 = m·g·sinθ，方向决定增减）
    // 旋转页为纯回转模型，无质量与质心偏距输入 → 倾斜角不参与任何计算（第 62 轮遗留项 #2 定稿）：
    //   保留输入框（便于记录样册工况），默认 0°，右列显示输入值本身并注明「不参与计算」
    rows.push(pairRow({
      key: 'tilt', label: '倾斜角度', userVal: cv.tilt, effVal: cv.tilt,
      unit: '°', badge: 'opt',
      hint: s.hasDirection ? undefined : '旋转页为纯回转直驱模型，倾斜角度不参与计算'
    }));

    rows.push(pairRow({
      key: 'tAmbient', label: '环境温度', userVal: cv.tAmbient, effVal: cv.tAmbient,
      unit: '°C', badge: 'req'
    }));

    rows.push(pairRow({
      key: 'uSupply', label: '供电母线', userVal: cv.uSupply, effVal: cv.uSupply,
      unit: 'VDC', badge: 'opt', min: 0
    }));

    el.innerHTML = rows.join('');

    el.querySelectorAll('input[type=number]').forEach(inp => {
      const key = inp.id.replace('f_', '');
      // 下限由 min 属性声明（质量/距离/时间/速度/加速度等不允许为负）
      const minAttr = inp.getAttribute('min');
      const minVal = minAttr == null ? null : Number(minAttr);

      inp.addEventListener('input', e => {
        let raw = e.target.value.trim();
        // 手动输入仍可绕过 min：输入即钳回下限，避免负值进入计算
        if (minVal != null && raw !== '') {
          const n = Number(raw);
          if (isFinite(n) && n < minVal) {
            raw = String(minVal);
            e.target.value = raw;
          }
        }
        inp.dataset.auto = raw === '' ? '1' : '0';
        inp.classList.toggle('derived', raw === '');
        curve()[key] = numOrNull(raw);
        if (key === 'tilt') syncDirectionFromTilt(el);
        // 直接透传的行：右列「计算结果」随输入同步（推算行由 syncCalcFields 覆盖）
        const cell = document.getElementById('r_' + key);
        if (cell) {
          const v = curve()[key];
          cell.textContent = isNum(v) ? fmt(v, 4) : '—';
          cell.className = 'cell-result' + (isNum(v) ? '' : ' blank');
        }
        renderComputed();
      });
      inp.addEventListener('blur', () => {
        if (inp.dataset.auto === '1' || inp.value === '') return;
        const v = numOrNull(inp.value);
        if (v != null) inp.value = String(v);
      });
    });
    el.querySelectorAll('input[name=dir]').forEach(r => {
      r.addEventListener('change', e => {
        // 方向仅作快捷设定：上 → 倾斜角 90°；下 → 270°（仍可手动改成任意角度）
        curve().direction = e.target.value;
        curve().tilt = e.target.value === 'down' ? 270 : 90;
        render();
      });
    });
  }

  /** 倾斜角自带方向：sinθ > 0 记为「上」，< 0 记为「下」；手动改角度时回写方向单选 */
  function syncDirectionFromTilt(scope) {
    const tilt = curve().tilt;
    if (!isNum(tilt)) return;
    const s = Math.sin(tilt * Math.PI / 180);
    if (Math.abs(s) < 1e-9) return;          // 0° / 180°：水平，无重力，保持原方向
    const dir = s < 0 ? 'down' : 'up';
    curve().direction = dir;
    const radio = scope.querySelector(`input[name=dir][value="${dir}"]`);
    if (radio) radio.checked = true;
  }

  function row(key, label, value, unit, badge, hint) {
    const b = badge === 'req' ? '<span class="req-star">*</span>'
      : badge === 'der' ? '<span class="badge der">推算</span>' : '<span class="badge opt">可选</span>';
    return `<div class="row">

  function row(key, label, value, unit, badge, hint) {
    const b = badge === 'req' ? '<span class="req-star">*</span>'
      : badge === 'der' ? '<span class="badge der">推算</span>' : '<span class="badge opt">可选</span>';
    return `<div class="row">
      <label>${label} ${b}</label>
      <div class="field-wrap"><input type="number" step="any" id="f_${key}" value="${value == null ? '' : value}"></div>
      <span class="unit">${unit}</span>
    </div>${hint ? `<div class="sub-label">${hint}</div>` : ''}`;
  }

  /** 只读的理论计算值行：统一紫色标注，与用户输入区分 */
  function calcRow(key, label, value, unit, hint, lifted) {
    return `<div class="row">
      <label>${label} <span class="badge calc">理论值</span></label>
      <div class="field-wrap">
        <input type="number" class="derived" readonly tabindex="-1" id="f_${key}"
               value="${value == null ? '' : fmt(value, 4)}">
      </div>
      <span class="unit">${unit}</span>
    </div>
    <div class="sub-label">${hint}${lifted ? '（已含 S 曲线上抬）' : ''}</div>`;
  }


  /* ---------------- 渲染：理论结果 ---------------- */
  /**
   * 理论结果：6 项卡片
   *   持续推力/力矩、峰值推力/力矩、最大加速度、最大速度、安全余量、总循环周期
   *   前两项与总循环周期来自理论计算；加速度/速度是同一条规划曲线的峰值；
   *   安全余量取自所选型号的实际应用值（未选电机为 —）。
   */
  function renderResults(cmp) {
    const s = schema();
    const el = document.getElementById('resultBody');
    if (!cmp || cmp.stage !== 'ok') {
      el.innerHTML = `<div class="error-text">${(cmp && cmp.error) || '请填写输入项'}</div>`;
      return;
    }
    const cv = cmp.curve, theo = cmp.theo;

    // 块状卡片：标签在上、数值在下、单位紧随数值（参考同类软件的指标卡）
    // 推力/力矩用斜杠合写，两个页签共用一套标签，无需随页签换词
    const card = (label, value, unit, unitCls, extraCls) => `<div class="kv-card ${extraCls || ''}">
        <span class="k">${label}</span>
        <span class="v"><span class="num">${value}</span><span class="u ${unitCls}">${unit}</span></span>
      </div>`;

    // 第 60 轮：三张时间卡片换成 最大加速度 / 最大速度 / 安全余量
    //   - 加速度、速度是运动规划的关键结果，比「加/匀/减各段耗时」更值得占据卡片位；
    //   - 安全余量取「电机实际应用值」里的安全系数口径（未选电机时为 —），
    //     这样它与安全余量面板的下限（默认 30%）可直接比较，不像列表里的 Safety 那样已扣掉门槛。
    //   各段耗时仍在输入区「计算结果」列与导出报告中可见。
    const aDisp = C.accelDisplay(state.kind, cv.aPeak);
    const vDisp = C.speedDisplay(state.kind, cv.vPeak);
    const safety = cmp.applied && isNum(cmp.applied.safety) ? cmp.applied.safety : null;

    el.innerHTML = `<div class="kv-cards">
      ${card('持续推力/力矩', one(theo.cont), s.forceUnit, 'u-force')}
      ${card('峰值推力/力矩', one(theo.peak), s.forceUnit, 'u-force')}
      ${card('最大加速度', one(aDisp), s.aUnit, 'u-time', 'is-time')}
      ${card('最大速度', one(vDisp), s.vUnit, 'u-time', 'is-time')}
      ${safety == null
        ? card('安全余量', '—', '', 'u-pct', 'is-margin')
        : card('安全余量', one(safety), '%', 'u-pct', 'is-margin')}
      ${card('总循环周期', one(theo.cycleTime), 's', 'u-time', 'is-time')}
    </div>`;
  }

  /* ---------------- 渲染：安全余量 ---------------- */
  function renderMargin() {
    const el = document.getElementById('marginBody');
    el.innerHTML = `
      <div class="slider-row">
        <label>下限</label>
        <input type="range" id="rngLow" min="0" max="100" step="5" value="${state.marginLow}">
        <span class="num">${state.marginLow}%</span>
      </div>
      <div class="slider-row">
        <label>上限</label>
        <input type="range" id="rngHigh" min="100" max="500" step="25" value="${state.marginHigh}">
        <span class="num">${state.marginHigh}%</span>
      </div>
      <label class="inline" style="cursor:pointer">
        <input type="checkbox" id="chkHigh" ${state.marginHighOn ? 'checked' : ''}> 加上限判定（余量过大仅提示）
      </label>
    `;
    document.getElementById('rngLow').addEventListener('input', e => {
      state.marginLow = Number(e.target.value);
      e.target.parentElement.querySelector('.num').textContent = state.marginLow + '%';
      renderComputed();          // 拖动滑杆时不重建滑杆本身
    });
    document.getElementById('rngHigh').addEventListener('input', e => {
      state.marginHigh = Number(e.target.value);
      e.target.parentElement.querySelector('.num').textContent = state.marginHigh + '%';
      renderComputed();
    });
    document.getElementById('chkHigh').addEventListener('change', e => {
      state.marginHighOn = e.target.checked; renderComputed();
    });
  }

  /* ---------------- 渲染：推荐电机 ---------------- */
  /**
   * 结构层：勾选项（页签变更时重建）
   *   第 52 轮去掉「推荐 / 全部型号」切换：本模块固定只列通过两条准入的型号，
   *   达不到下限的型号不再出现（不再标红列出，红线的复核改由详情/报告承担）。
   */
  function renderCandidateShell() {
    const el = document.getElementById('candBody');
      </div>`;
      head = el.querySelector('.cand-head');
    }

    // 勾选项随页签变化：只保留结构 / 冷却（第 43 轮）
    // 不再出现「直线 / 力矩 / 音圈」——候选列表已按页签只放可计算的电机，类型 chip 冗余。
    // 「音圈」既是电机类型又是结构名，作为结构 chip 一并去掉（与用户指定的选项保持一致）。
    // 第 58 轮：旋转页去掉「超声波力矩电机」结构 chip 与「标准 / 特殊」中孔 chip。
    //   这些取值仍是电机的属性（录入表单、电机库与详情展示照旧），只是不再作为筛选维度。
    const typeLabels = new Set(D.TYPE_ORDER.map(t => D.MOTOR_TYPES[t].label));
    const hiddenStructures = new Set(['超声波力矩电机']);
    const union = key => [...new Set(
      D.TYPE_ORDER
        .filter(t => D.MOTOR_TYPES[t].axis === state.kind)
        .flatMap(t => D.MOTOR_TYPES[t][key] || [])
    )];
    const chipsHtml = [
      ...union('structures')
        .filter(v => !typeLabels.has(v) && !hiddenStructures.has(v))
        .map(v => chip('structure', v)),
      ...union('coolings').map(v => chip('cooling', v))
    ].join('');
    const row = document.getElementById('filterRow');
    if (row.dataset.kind !== state.kind) {
      row.dataset.kind = state.kind;
      row.innerHTML = chipsHtml;
      row.querySelectorAll('.filter-chip').forEach(c => {
        c.addEventListener('click', () => {
          const g = c.dataset.group, v = c.dataset.value;
          const set = state.filters[g];
          if (set.has(v)) set.delete(v); else set.add(v);
          c.classList.toggle('active', set.has(v));
          renderComputed();
        });
      });
    }

  }

  /** 数据层：推荐列表内容（输入变化时刷新）；只列通过两条准入的型号 */
  function renderCandidateBody(cmp) {
    const evals = cmp && cmp.stage === 'ok' ? cmp.evals : [];
    const list = document.getElementById('list');
    const shown = evals.filter(e => e.pass);

    if (!shown.length) {
      list.innerHTML = `<div class="empty-state">
        当前无合规型号<br>可放宽下限、清除勾选过滤，或补充电机库
      </div>`;
      return;
    }

    // 每行只显示型号（含配置）与安全余量（参考样册式清单）
    // 混合库下补一个类型徽标，否则同页里「AVM12-6.4」看不出是音圈还是直线
    let html = '';
    // 列表只剩通过准入的型号：跨量纲的「不适用」行已随之消失，
    // 因此每行都必定是同一量纲、可点选，无需再拦截
    shown.forEach(e => {
      const m = e.motor;
      const safety = e.headroom != null ? 'Safety: ' + fmt(e.headroom * 100, 0) + '%' : '—';
      html += `<div class="cand-item ${e.verdict} ${m.id === curve().motorId ? 'selected' : ''}"
          data-id="${m.id}" title="${e.reasons ? e.reasons.join('\n') : ''}">
        <span class="type-tag ${m.type}">${D.MOTOR_TYPES[m.type].label}</span>
        <span class="cand-name">${motorLabel(m)}</span>
        <span class="cand-safety">(${safety})</span>
      </div>`;
    });
    list.innerHTML = html;

    list.querySelectorAll('.cand-item').forEach(item => {
      item.addEventListener('click', () => {
        curve().motorId = item.dataset.id;
        renderComputed();
      });
    });
  }

  function chip(group, value) {
    const active = state.filters[group].has(value) ? 'active' : '';
    return `<span class="filter-chip ${active}" data-group="${group}" data-value="${value}">${value}</span>`;
  }

  /* ---------------- 渲染：选中电机详情 ---------------- */
  /**
   * 电机参数表：按「电机类型」取样册式单列展示
   *   只列参考面板中的参数（需求第 31 轮），字段顺序与录入模板一致
   */
  const MOTOR_PARAMS = {
    linear: [
      { key: 'forceCont', label: '持续推力', unit: 'N' },
      { key: 'forcePeak', label: '峰值推力', unit: 'N' },
      { key: 'Km', label: '电机常数', unit: 'N/SqRt(W)' },
      { key: 'Kf', label: '推力常数', unit: 'N/A' },
      { key: 'Ke', label: '反电动势常数', unit: 'V/(m/s)' },
      { key: 'Icont', label: '连续电流', unit: 'A' },
      { key: 'Ipeak', label: '峰值电流', unit: 'A' },
      { key: 'L', label: '电感', unit: 'mH' },
      { key: 'R25', label: '绕线电阻', unit: 'ohms' },
      { key: 'Prated', label: '额定功率', unit: 'W' },
      { key: 'Pmax', label: '最大功率', unit: 'W' },
      { key: 'moverMass', label: '线圈重量', unit: 'kg' },
      { key: 'coilLen', label: '线圈长度', unit: 'mm' }
    ],
    torque: [
      { key: 'torqueCont', label: '持续力矩', unit: 'Nm' },
      { key: 'torquePeak', label: '峰值力矩', unit: 'Nm' },
      { key: 'Km', label: '电机常数', unit: 'Nm/SqRt(W)' },
      { key: 'Kt', label: '扭力常数', unit: 'Nm/A' },
      { key: 'Ke', label: '反电动势常数', unit: 'V/(rad/s)' },
      { key: 'Icont', label: '连续电流', unit: 'A' },
      { key: 'Ipeak', label: '峰值电流', unit: 'A' },
      { key: 'L', label: '电感', unit: 'mH' },
      { key: 'R25', label: '绕线电阻', unit: 'ohms' },
      { key: 'Prated', label: '额定功率', unit: 'W' },
      { key: 'Pmax', label: '最大功率', unit: 'W' },
      { key: 'J', label: '转动惯量', unit: 'kgm^2' }
    ],
    coil: [
      { key: 'stroke', label: '行程', unit: 'mm' },
      { key: 'forceCont', label: '持续推力', unit: 'N' },
      { key: 'forcePeak', label: '峰值推力', unit: 'N' },
      { key: 'Km', label: '电机常数', unit: 'N/SqRt(W)' },
      { key: 'Kf', label: '力常数', unit: 'N/A' },
      { key: 'Ke', label: '反电动势常数', unit: 'V/(m/s)' },
      { key: 'Icont', label: '连续电流', unit: 'A' },
      { key: 'Ipeak', label: '峰值电流', unit: 'A' },
      { key: 'L', label: '电感', unit: 'mH' },
      { key: 'R25', label: '绕线电阻', unit: 'ohms' },
      { key: 'Prated', label: '额定功率', unit: 'W' },
      { key: 'Pmax', label: '最大功率', unit: 'W' },
      { key: 'moverMass', label: '线圈质量', unit: 'kg' },
      { key: 'coreMass', label: '铁芯质量', unit: 'g' },
      { key: 'gap', label: '运动间隙', unit: 'mm' }
    ]
  };

  /**
   * 参数取值
   *   额定/最大功率按铜耗 I²R 折算；
   *   电机常数 Km 有派生公式，未录入时自动算出（第 46 轮）：
   *     三相（直线 / 力矩）：Km = Kt / √(1.5·R_ll)     ← 1.5 即三相铜损系数
   *     单相（音圈）      ：Km = Kf / √R
   *   已用 4 份样册实测验证（AUM2 3.573≈3.6、AVM12 0.499≈0.5、ADR110-B136 0.516≈0.51、ADR110-B113 0.297≈0.30）。
   */
  function motorParamValue(m, key) {
    if (key === 'Prated') {
      return isNum(m.Icont) && isNum(m.R25) ? m.Icont * m.Icont * m.R25 : null;
    }
    if (key === 'Pmax') {
      return isNum(m.Ipeak) && isNum(m.R25) ? m.Ipeak * m.Ipeak * m.R25 : null;
    }
    if (key === 'Km' && !isNum(m.Km)) {
      const kt = isNum(m.Kf) ? m.Kf : (isNum(m.Kt) ? m.Kt : null);
      if (isNum(kt) && isNum(m.R25) && m.R25 > 0) {
        const factor = m.type === 'coil' ? 1 : 1.5;   // 音圈单相，无三相 1.5 系数
        return kt / Math.sqrt(factor * m.R25);
      }
    }
    return isNum(m[key]) ? m[key] : null;
  }

  function renderMotor() {
    const el = document.getElementById('motorBody');
    const list = pool();
    const selId = curve().motorId;
    const sel = list.find(m => m.id === selId) || null;
    // 详情表随选中电机的类型切换；未选中时按页签给一套默认
    const paramType = sel ? sel.type : (state.kind === 'linear' ? 'linear' : 'torque');

    const opts = list.map(m =>
      `<option value="${m.id}" ${m.id === selId ? 'selected' : ''}>${
        D.MOTOR_TYPES[m.type].label + ' · ' + motorLabel(m)
      }</option>`
    ).join('');

    let html = `<div class="motor-pick">
        <label for="motorSelect">电机：</label>
        <select id="motorSelect">
          <option value="" ${selId ? '' : 'selected'}>— 未选取 —</option>
          ${opts}
        </select>
      </div><div class="motor-params">`;

    MOTOR_PARAMS[paramType].forEach(p => {
      const v = sel ? motorParamValue(sel, p.key) : null;
      const dec = (p.key === 'Prated' || p.key === 'Pmax') ? 1 : 4;   // 功率取 1 位小数
      html += `<div class="motor-param">
          <span class="p-label">${p.label}：</span>
          <span class="p-value ${v == null ? 'muted' : ''}">${v == null ? '—' : fmt(v, dec)}</span>
          <span class="p-unit">${p.unit}</span>
        </div>`;
    });
    html += '</div>';
    el.innerHTML = html;

    document.getElementById('motorSelect').addEventListener('change', e => {
      curve().motorId = e.target.value || null;
      renderComputed();
    });
  }

  /* ---------------- 渲染：实际应用值 ---------------- */
  /**
   * 实际应用值：只输出参考软件的 7 项（第 45 轮）
   *   线圈温度 / 连续电流 / 峰值电流 / 直流母线电压 / 安全系数 / 持续力负载系数 / 峰值力负载系数
   *   中间量（允许持续电流、R_hot、相电压分解、含电机惯量峰值）不再输出
   */
  function renderApplied(cmp) {
    const s = schema();
    const el = document.getElementById('appliedBody');
    if (!cmp || cmp.stage !== 'ok' || !cmp.applied) {
      el.innerHTML = '<div class="empty-state">选取电机后显示：线圈温度、电流、母线电压、安全系数</div>';
      return;
    }
    const a = cmp.applied;
    const verdictCls = a.thermalPass === true ? 'pass' : a.thermalPass === false ? 'fail' : 'unknown';
    const verdictTxt = a.thermalPass === true ? '电机选型合适' : a.thermalPass === false ? '线圈温度超限' : '热校核数据不足';
    const forceName = s.forceUnit === 'N·m' ? '力矩' : '力';

    // 算不出来的项要说明「缺什么」，否则只显示「—」分不清是缺数据还是算错
    const rows = [
      ['线圈温度', a.tCoil, '°C', a.tCoil == null ? '缺 热耗散常数 Kth' : ''],
      ['连续电流', a.iContRms, 'A', a.iContRms == null ? '缺 ' + (s.forceUnit === 'N·m' ? '转矩常数 Kt' : '力常数 Kf') : ''],
      ['峰值电流', a.iPeakRms, 'A', a.iPeakRms == null ? '缺 ' + (s.forceUnit === 'N·m' ? '转矩常数 Kt' : '力常数 Kf') : ''],
      ['直流母线电压', a.uReq, 'V', a.uReq == null ? '缺 ' + (a.uMissing || []).join(' / ') : ''],
      ['安全系数', a.safety, '%', ''],
      [`持续${forceName}负载系数`, a.loadCont, '%', ''],
      [`峰值${forceName}负载系数`, a.loadPeak, '%', '']
    ];

    let html = `<div class="inline" style="margin-bottom:3px">
        <span class="verdict ${verdictCls}">${verdictTxt}</span>
        ${a.overUbus ? '<span class="verdict fail">需求电压 &gt; 电机 Ubus 耐压</span>' : ''}
      </div>
      <table class="kv-table applied spaced">`;
    rows.forEach(([label, v, unit, missing]) => {
      if (v == null && missing) {
        // 缺参数：用灰字写明原因，不给出误导性的数字
        html += `<tr><td>${label}</td>
          <td class="value muted-note" colspan="2">—（${missing}）</td></tr>`;
      } else {
        html += `<tr><td>${label}</td><td class="value">${one(v)}</td><td class="unit-cell">${unit}</td></tr>`;
      }
    });
    html += `</table>`;

    // 供电结论（需求 7.4）
    if (a.supply) {
      html += `<div class="${a.supply.pass ? 'info-text' : 'error-text'}" style="margin-top:5px">
        供电 ${fmt(a.supply.have, 1)} VDC · 所需 ${fmt(a.supply.need, 1)} VDC → ${a.supply.text}
      </div>`;
    } else if (isNum(a.uReq)) {
      html += `<div class="hint-arrow">填入「供电母线（VDC）」可获得供电是否够的结论（需求 7.4）</div>`;
    }
    el.innerHTML = html;
  }

  /* ---------------- 渲染：图表 ---------------- */
  /**
   * 由一条方案的完整计算结果构造两张图的数据（纯函数，不碰 DOM）
   *   导出报告需要为每条方案各生成一套图，因此必须与屏幕上的 canvas 解耦（第 42 轮）
   */
  /* ---------------- 渲染：图表 ---------------- */
  /**
   * 由一条方案的完整计算结果构造两张图的数据（纯函数，不碰 DOM）
   *   导出报告需要为每条方案各生成一套图，因此必须与屏幕上的 canvas 解耦（第 42 轮）

    // 力剖面：加速段 m·a+F_res+G，匀速段 F_res+G，减速段 −m·a+F_res+G
    // 第 45 轮：与「实际应用值」同口径——只用用户填的负载，不叠加电机线圈质量
    const mass = cv.load || 0;
    const resist = cv.resist || 0;
    const grav = state.kind === 'linear'
      ? mass * 9.81 * Math.sin((cv.tilt || 0) * Math.PI / 180)
      : 0;
    const forceAt = (acc) => mass * acc * factor + resist + grav;

    const samp = C.sampleCurve(cmp.curve, forceAt);
    const speedPts = samp.vPts.map(p => ({ x: p.t, y: dispV(p.v) }));
    const forcePts = samp.fPts.map(p => ({ x: p.t, y: p.f }));

    // 阶段时间点：加速结束、匀速结束、全程结束
    const tLabel = t => (Math.abs(t) < 1e-9 ? '0' : t.toFixed(3));
    const cruiseEnd = cmp.curve.tAccel + Math.max(cmp.curve.tCruise, 0);
    const xTicks = [{ x: 0, label: '0' }, { x: cmp.curve.tAccel, label: tLabel(cmp.curve.tAccel) }];
    if (cmp.curve.tCruise > 1e-9) xTicks.push({ x: cruiseEnd, label: tLabel(cruiseEnd) });
    xTicks.push({ x: cmp.curve.time, label: tLabel(cmp.curve.time) });

    // 阶段分界线只画到对应时刻的曲线点（各线高度不同）
    const speedVlines = [{ x: cmp.curve.tAccel, y: dispV(cmp.curve.vPeak) }];
    if (cmp.curve.tCruise > 1e-9) speedVlines.push({ x: cruiseEnd, y: dispV(cmp.curve.vPeak) });

    // 用户速度上限只在自定义模式有意义（点到点下它是理论值，不画参考线）
    const speedHlines = [];
    if (state.mode === 'custom' && isNum(cv.vMax)) {
      speedHlines.push({ y: cv.vMax, label: '用户速度上限', color: '#8a6100' });
    }
    // 用户速度上限只在自定义模式有意义（点到点下它是理论值，不画参考线）
    const speedHlines = [];
    if (state.mode === 'custom' && isNum(cv.vMax)) {
      speedHlines.push({ y: cv.vMax, label: '用户速度上限', color: '#8a6100' });
    }

    const forceVlines = [{ x: cmp.curve.tAccel, y: forceAt(cmp.curve.aPeak) }];
    if (cmp.curve.tCruise > 1e-9) forceVlines.push({ x: cruiseEnd, y: forceAt(0) });

    const forceHlines = [];
    if (cmp.selected) {
      const capCont = state.kind === 'linear' ? cmp.selected.forceCont : cmp.selected.torqueCont;
      const capPeak = state.kind === 'linear' ? cmp.selected.forcePeak : cmp.selected.torquePeak;
      forceHlines.push({ y: capPeak, label: '电机峰值能力', color: '#b02a1e' });
      forceHlines.push({ y: capCont, label: '电机连续能力', color: '#1f7a3d' });
    }

    return {
      speed: { points: speedPts, color: '#1f4e9c', yLabel: s.units.vChart, hlines: speedHlines, xTicks, vlines: speedVlines },
      force: { points: forcePts, color: '#1f4e9c', yLabel: s.units.fChart, hlines: forceHlines, xTicks, vlines: forceVlines },
      hint: `${cmp.curve.shape === 'triangle' ? '三角形' : '梯形'}${cmp.curve.smoothApplied ? ' · S 曲线 ' + state.tau + ' ms' : ''}`
    };
  }

  function renderCharts(cmp) {
    const speedCanvas = document.getElementById('chartSpeed');
    const forceCanvas = document.getElementById('chartForce');
    document.getElementById('capSpeed').textContent = '图1: 速度 / 时间图';
    document.getElementById('capForce').textContent = state.kind === 'linear'
      ? '图2: 推力 / 时间图' : '图2: 力矩 / 时间图';

    if (!cmp || cmp.stage !== 'ok') {
      window.MiniChart.draw(speedCanvas, { points: [] });
      window.MiniChart.draw(forceCanvas, { points: [] });
      document.getElementById('chartHint').textContent = '无数据';
      return;
    }

    const d = chartData(cmp, curve());
    window.MiniChart.draw(speedCanvas, d.speed);
    window.MiniChart.draw(forceCanvas, d.force);
    document.getElementById('chartHint').textContent = d.hint;
  }

  /**
   * 离屏渲染一条方案的曲线图，返回 PNG Data URI
   *   导出报告里每条方案各带自己的图，不能再取屏幕上当前那条（第 42 轮）
   */
  function renderChartsToDataURI(cmp, cv) {
    const d = chartData(cmp, cv);
    const W = 760, H = 260;
    const make = opts => {
      const canvas = document.createElement('canvas');
      canvas.style.width = W + 'px';
      canvas.style.height = H + 'px';
      canvas.width = W;
      canvas.height = H;
      // MiniChart 按 clientWidth/clientHeight 绘制，离屏元素需先挂到 DOM 之外但可测尺寸
      canvas.style.position = 'absolute';
      canvas.style.left = '-9999px';
      document.body.appendChild(canvas);
      window.MiniChart.draw(canvas, opts);
      const uri = canvas.toDataURL('image/png');
      canvas.remove();
      return uri;
    };
    return { speed: make(d.speed), force: make(d.force) };
  }

  /* ---------------- 主渲染 ---------------- */
  /** 重算并刷新所有「结果类」面板（不改动输入控件，避免输入时失焦） */
  function renderComputed() {
    const cmp = compute();
    syncCalcFields(cmp);        // 点到点的「最大速度 / 最大加速度」理论值随输入变化
    renderResults(cmp);
    renderCandidateBody(cmp);
    renderMotor(cmp);
    renderApplied(cmp);
    renderCharts(cmp);
  }

  /** 更新「计算结果」列与形状标识（不重建 DOM，保持输入焦点） */
  function syncCalcFields(cmp) {
    const ok = cmp && cmp.stage === 'ok';

    // 形状自动判定标识（自定义模式）
    const chip = document.getElementById('shapeAutoChip');
    if (chip) {
      chip.textContent = ok
        ? (cmp.curve.shape === 'triangle' ? '▲ 三角形' : '⏢ 梯形')
        : '—';
      chip.classList.toggle('tri', ok && cmp.curve.shape === 'triangle');
    }

    // 点到点：两个理论值在右列；自定义：三项在右列随求解变化
    const tKey = state.mode === 'p2p' ? 'time' : 'time';
    const aKey = state.mode === 'p2p' ? 'aCalc' : 'aMax';
    const vKey = state.mode === 'p2p' ? 'vCalc' : 'vMax';

    const put = (key, val) => {
      const cell = document.getElementById('r_' + key);
      if (!cell) return;
      if (val == null) {
        cell.textContent = '—';
        cell.className = 'cell-result blank';
      } else {
        const autoEl = document.getElementById('f_' + key);
        // 该行输入为空（由系统算出）时，右列以紫色强调；用户已填则用普通样式
        const isAuto = !autoEl || autoEl.dataset.auto === '1';
        cell.textContent = fmt(val, 4);
        cell.className = 'cell-result' + (isAuto ? ' calc' : '');
      }
    };

    put(tKey, ok ? cmp.curve.time : null);
    put(aKey, ok ? C.accelDisplay(state.kind, cmp.curve.aPeak) : null);
    put(vKey, ok ? C.speedDisplay(state.kind, cmp.curve.vPeak) : null);
  }

  /** 全量渲染：切换页签、模式、方案或过滤条件时使用 */
  function render() {
    const cmp = compute();
    renderMode();
    renderCurveBar();
    renderInputs(cmp);
    renderMargin();
    renderResults(cmp);
    renderCandidateShell();
    renderCandidateBody(cmp);
    renderMotor(cmp);
    renderApplied(cmp);
    renderCharts(cmp);
  function applyPickView() {
    const cand = document.getElementById('candBody');
    const motor = document.getElementById('motorBody');
    if (!cand || !motor) return;
    const isCand = state.pickView === 'cand';
    cand.hidden = !isCand;
    motor.hidden = isCand;
    document.querySelectorAll('#panelToggle button').forEach(b =>
      b.classList.toggle('active', b.dataset.panel === state.pickView));
  }

  document.getElementById('panelToggle').addEventListener('click', e => {
    const btn = e.target.closest('button[data-panel]');
    if (!btn) return;
    state.pickView = btn.dataset.panel;
    applyPickView();
  });

  /* ---------------- 顶栏 / 弹窗 ---------------- */
  function toast(msg) {
    const el = document.getElementById('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._t);
    el._t = setTimeout(() => el.classList.remove('show'), 1800);
  }

  document.getElementById('tabs').addEventListener('click', e => {
    const tab = e.target.closest('.tab');
    if (!tab) return;
    state.kind = tab.dataset.kind;
    document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t === tab));
    // 过滤条件按页签清空
    state.filters = { structure: new Set(), cooling: new Set() };
    render();
  });

  document.getElementById('machineName').addEventListener('input', e => {
    document.getElementById('machineName').dataset.v = e.target.value;
  });
  document.getElementById('axisName').addEventListener('input', e => {
    document.getElementById('axisName').dataset.v = e.target.value;
  });

  document.getElementById('btnImport').onclick = () => openModal(importModal());
  document.getElementById('btnExport').onclick = () => openModal(reportModal());

  /* ---------- 弹窗基础 ---------- */
  function openModal(content) {
    closeModal();
    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.id = 'modalMask';
    mask.innerHTML = `<div class="modal ${content.wide ? 'wide' : ''}">
      <div class="modal-head">${content.title}<span class="close" id="modalClose">✕</span></div>
      <div class="modal-body">${content.body}</div>
      <div class="modal-foot">${content.foot || '<button class="btn primary" id="modalOk">关闭</button>'}</div>
    </div>`;
    document.body.appendChild(mask);
    mask.addEventListener('click', e => { if (e.target === mask) closeModal(); });
      <div class="modal-body">${content.body}</div>
      <div class="modal-foot">${content.foot || '<button class="btn primary" id="modalOk">关闭</button>'}</div>
    </div>`;
    document.body.appendChild(mask);
    mask.addEventListener('click', e => { if (e.target === mask) closeModal(); });
    document.getElementById('modalClose').onclick = closeModal;
    const ok = document.getElementById('modalOk');
    if (ok && !content.onOk) ok.onclick = closeModal;
    else if (ok && content.onOk) ok.onclick = content.onOk;
    if (content.after) content.after();
  }

  function closeModal() {
    const m = document.getElementById('modalMask');
    if (m) m.remove();
  }

  /* ---------- 导入电机参数 ---------- */
  function importModal() {
    return {
      title: '导入电机参数',
      body: `
        <div class="import-step">
          <h4>通道 1：手工录入（v1）</h4>
          <p>界面内逐条新增／编辑，字段与样册口径一致（字段字典唯一）。</p>
          <button class="btn" id="btnManual">打开手工录入表单</button>
        </div>
        <div class="import-step">
          <h4>通道 2：样册识别（后续增强，非 v1 阻塞项）</h4>
          <p>上传 PDF 或图片 → 逐型号生成<b>草稿</b> → 标注来源页 → 人工核对确认后才入库。<b>绝不静默写库</b>。</p>
          <button class="btn" id="btnPdf" disabled>上传样册（后续版本）</button>
        </div>
      `,
      foot: '<button class="btn primary" id="modalOk">关闭</button>',
      after: () => {
        document.getElementById('btnManual').onclick = () => openModal(manualModal());
      }
    };
  }

  /**
   * 手工录入电机（第 38 轮）
   *   「电机类型」决定参数字段模板：直线（AKM30 式）/ 力矩（ADR110 式）/ 音圈（AVM 式）
   *   「配置」（串联 / 并联 / 默认）下移一行保留——它仍是型号主键的一部分
   *   字段模板由 D.motorFieldGroups[type] 提供，与详情表共用同一份字段字典
   */
  function manualModal() {
    // 录入入口两个页签都能开，但从哪个页签打开就默认哪一类，减少误录
    let type = state.kind === 'rotary' ? 'torque' : 'linear';

    const typeRow = () => `<div class="row"><label>电机类型</label>
        <div class="field-wrap"><select id="mi_type">${
          D.TYPE_ORDER.map(t => `<option value="${t}" ${t === type ? 'selected' : ''}>${D.MOTOR_TYPES[t].label}</option>`).join('')
        }</select></div><span class="unit"></span></div>`;

    /**
     * 按当前类型生成参数区（分组标题 + 字段行）
     * 第 50 轮：**组内必填项前置、选填项尾随**，保证整个表单从上往下看
     * 「所有必填 → 所有选填」，录入时不必在必填与选填之间来回跳。
     *   注意：三套模板的必填项都在「性能参数」组内，机械参数与准入补充组目前全为选填，
     *   因此「组内排序」即等价于「全局排序」。若将来有机械参数字段改为必填，
     *   需同时把它们移到性能参数组（或改为全局排序），否则会破坏该不变量。
     */
    /**
     * 冷却方式在标签中的写法（第 51 轮）
     *   样册的「自冷」= 本产品的「普通」冷却；水冷/气冷直接同名。
     *   录入表单里 5 处标签含 `{cool}` 占位符，随所选冷却方式实时替换，
     *   避免选了水冷却仍显示「(自冷)」这种自相矛盾的标注。
     */
    const COOL_LABEL = { '普通': '自冷', '水冷': '水冷', '气冷': '气冷' };
    const coolWord = () => {
      const el = document.getElementById('mi_cooling');
      return COOL_LABEL[el && el.value] || '自冷';
    };
    const renderLabel = label => label.replace('{cool}', coolWord());

    function fieldsHtml() {
      const groups = D.motorFieldGroups[type];
      return groups.map(g => {
        const sorted = g.fields.slice().sort((a, b) => (b.req ? 1 : 0) - (a.req ? 1 : 0));
        return `
        <div class="field-group-title lv1${g.optionalAdd ? ' opt' : ''}">${g.title}</div>
        ${sorted.map(f => `<div class="row${f.req ? ' is-req' : ''}">
          <label>${renderLabel(f.label)}${f.req ? '<span class="req-star">*</span>' : ''}</label>
          <div class="field-wrap"><input type="number" step="any" id="mi_${f.key}" value=""
            ${f.req ? 'required aria-required="true"' : ''}></div>
          <span class="unit">${f.unit}</span>
        </div>`).join('')}`;
      }).join('');
    }

    /**
     * 只替换已有标签里的冷却字样，不重建 DOM
     * （重建会丢失已填内容，切换冷却方式只是改标注，用户输入应保留）
     */
    function refreshCoolLabels() {
      D.motorFieldGroups[type].forEach(g => g.fields.forEach(f => {
        if (!f.label.includes('{cool}')) return;
        const el = document.getElementById('mi_' + f.key);
        if (!el) return;
        const label = el.closest('.row').querySelector('label');
        if (!label) return;
        const star = label.querySelector('.req-star');
        label.textContent = renderLabel(f.label);
        if (star) label.appendChild(star);
      }));
    }

    /** 配置 / 结构类型 / 冷却方式 / 中孔 的选项随电机类型变化 */
    function metaHtml() {
      const meta = D.MOTOR_TYPES[type];
      const sel = (id, label, values) => values.length
        ? `<div class="row"><label>${label}</label><div class="field-wrap"><select id="${id}">${
            values.map(v => `<option>${v}</option>`).join('')}</select></div><span class="unit"></span></div>`
        : '';
      // 配置是三选（串联/并联/默认）而不是自由文本：手打错会让主键重复
      return sel('mi_config', '配置', meta.configs)
        + sel('mi_structure', '结构类型', meta.structures)
        + sel('mi_cooling', '冷却方式', meta.coolings)
        + sel('mi_bore', '中孔规格', meta.bores);
    }

    function bodyHtml() {
      return `
        <div id="miHead">
          <div class="row"><label>型号名</label><div class="field-wrap"><input type="text" id="mi_name" placeholder="如 ADR110-B136"></div><span class="unit"></span></div>
          ${typeRow()}
        </div>
        <div id="miMeta">${metaHtml()}</div>
        <div class="error-text" id="miError" hidden></div>
        <div id="miFields">${fieldsHtml()}</div>
        <div class="sub-label">带 <span class="req-star">*</span> 为必填，缺项无法入库；其余可留空（留空的项该型号判为「信息不全、无法判定」）。</div>
      `;
    }

    return {
      title: '手工录入电机',
      wide: true,
      body: bodyHtml(),
      foot: '<button class="btn" id="modalOk2">取消</button><button class="btn primary" id="btnSaveMotor">保存入库</button>',
      after: () => {
        document.getElementById('modalOk2').onclick = closeModal;

        // 冷却方式切换：只替换标签中的「自冷/水冷/气冷」字样，不重建 DOM（保留已填值）
        document.getElementById('miMeta').addEventListener('change', e => {
          if (e.target.id === 'mi_cooling') refreshCoolLabels();
        });

        // 类型切换：记住已填过的字段，换模板时清空它们的输入框
        document.getElementById('mi_type').addEventListener('change', e => {
          const next = e.target.value;
          if (next === type) return;
          const keep = {};
          D.motorFieldGroups[type].forEach(g => g.fields.forEach(f => {
            const v = document.getElementById('mi_' + f.key);
            if (v && numOrNull(v.value) != null) keep[f.key] = v.value;
          }));
          type = next;
          document.getElementById('miMeta').innerHTML = metaHtml();
          // 注意顺序：先重建 miMeta（冷却方式回到该类型的默认项），
          // 再生成字段区——fieldsHtml 内部读的是重建后的 mi_cooling 值
          document.getElementById('miFields').innerHTML = fieldsHtml();
          Object.keys(keep).forEach(k => {
            const el = document.getElementById('mi_' + k);
            if (el) el.value = keep[k];
          });
          // 换模板后旧类型的缺项提示已失效，清掉
          const err = document.getElementById('miError');
          if (err) { err.hidden = true; err.textContent = ''; }
          document.querySelectorAll('input.missing').forEach(i => i.classList.remove('missing'));
          const save = document.getElementById('btnSaveMotor');
          if (save) save.disabled = false;
          dirty = false;            // 新模板尚未尝试保存，不显示错误
          revalidate();
          bindRevalidate();
        });

        /**
         * 表单校验（保存前与补填时共用一份逻辑，避免提示与实际状态不一致）
         *   返回 { ok, message, empty[] }；empty 为仍缺的必填项
         */
        function validate() {
          const read = id => {
            const el = document.getElementById(id);
            return el ? el.value : '';
          };
          const name = document.getElementById('mi_name').value.trim();
          if (!name) {
            return { ok: false, message: '录入失败：请填写型号名', empty: [], nameMissing: true };
          }
          const empty = D.motorFieldGroups[type]
            .flatMap(g => g.fields)
            .filter(f => f.req && numOrNull(read('mi_' + f.key)) == null);
          if (empty.length) {
            return {
              ok: false,
              message: `录入失败：请填写标 * 的必填项（缺 ${empty.length} 项）\n${empty.map(f => f.label).join('、')}`,
              empty
            };
          }
          return { ok: true, empty: [] };
        }

        /**
         * 已报过错的表单，在用户补填时重新校验：
         * 缺项列表与数量实时更新，填齐后提示自动消失
         */
        let dirty = false;        // 是否已点过一次保存（未报错前不打扰用户）
        function revalidate() {
          if (!dirty) return;
          const err = document.getElementById('miError');
          const name = document.getElementById('mi_name');
          const v = validate();
          if (v.ok) {
            if (err) { err.hidden = true; err.textContent = ''; }
          } else if (err) {
            err.textContent = v.message;
            err.hidden = false;
          }
          // 标红只标真正缺的项；已填的项立即恢复
          document.querySelectorAll('#miFields input.missing').forEach(i => i.classList.remove('missing'));
          document.querySelectorAll('#miFields label.unfilled').forEach(l => l.classList.remove('unfilled'));
          v.empty.forEach(f => {
            const el = document.getElementById('mi_' + f.key);
            if (!el) return;
            el.classList.add('missing');
            const label = el.closest('.row') && el.closest('.row').querySelector('label');
            if (label) label.classList.add('unfilled');
          });
          if (name) name.classList.toggle('missing', !!v.nameMissing);
          document.getElementById('btnSaveMotor').disabled = !v.ok;
        }

        function bindRevalidate() {
          document.querySelectorAll('#miFields input').forEach(inp =>
            inp.addEventListener('input', revalidate));
          const name = document.getElementById('mi_name');
          if (name) name.addEventListener('input', revalidate);
        }
        bindRevalidate();

        document.getElementById('btnSaveMotor').onclick = () => {
          const read = id => {
            const el = document.getElementById(id);
            return el ? el.value : '';
          };
          /** 校验失败：显示原因、标红缺项、聚焦第一个缺项 */
          function reject(message, focusKey) {
            const err = document.getElementById('miError');
            if (err) { err.textContent = message; err.hidden = false; }
            toast(message.split('\n')[0]);
            const el = focusKey ? document.getElementById(focusKey) : null;
            if (el) { el.classList.add('missing'); el.focus(); el.scrollIntoView({ block: 'center' }); }
            else {
              const nameEl = document.getElementById('mi_name');
              nameEl.classList.add('missing'); nameEl.focus();
            }
          }

          dirty = true;      // 从此刻起补填会实时刷新缺项列表

          // 0) 型号名 + 1) 带 * 的必填项：全部填齐才允许入库
          const v = validate();
          if (!v.ok) {
            // 先统一标红全部缺项，再把视图定位到第一处
            revalidate();
            const first = v.nameMissing ? 'mi_name' : 'mi_' + v.empty[0].key;
            const el = document.getElementById(first);
            if (el) { el.focus(); el.scrollIntoView({ block: 'center' }); }
            toast(v.message.split('\n')[0]);
            return;
          }

          const name = read('mi_name').trim();
          const meta = D.MOTOR_TYPES[type];
          const config = (read('mi_config') || '').trim() || meta.configs[0];
          // 主键 = 型号名 + 配置名（需求 6.1）：同组合已存在时不允许再次入库
          const dupId = name + ' / ' + config;
          if (state.motors.some(x => x.id === dupId)) {
            reject(`录入失败：「${dupId}」已存在，请改用其他型号名或配置`, 'mi_name');
            return;
          }
          const m = {
            id: dupId,
            name, type, axis: meta.axis, config,
            mfr: '手工', series: '—',
            structure: read('mi_structure') || meta.structures[0],
            cooling: read('mi_cooling') || meta.coolings[0],
            bore: read('mi_bore') || undefined,
            source: { doc: '手工录入', page: '—', date: new Date().toISOString().slice(0, 10), method: '手工录入' }
          };
          // 按录入单位 → 内部单位写库（音圈线圈质量 g → kg，见字段 factor）
          D.motorFieldGroups[type].forEach(g => g.fields.forEach(f => {
            const v = numOrNull(read('mi_' + f.key));
            if (v != null) m[f.key] = v * (f.factor || 1);
          }));
          state.motors.push(m);
          closeModal(); render();
          toast(`已入库（${meta.label} · ${m.id}）`);
        };
      }
    };
  }

  /* ---------- 导出报告 ---------- */
  /**
   * 导出报告（第 42 轮）：按「项目 → 轴 → 方案」层级，把**当前页签下的全部方案**
   * 逐条导出到同一份报告，而不是只导当前正在看的那条。
   *   每条方案各带自己的输入、理论结果、推荐清单、选定型号、实际应用值与两张曲线图。
   *   曲线图用离屏 canvas 生成（屏幕上只显示当前方案）。
   */
  function reportModal() {
    // 先刷新当前方案，保证报告与实际界面一致
    compute();
    const s = schema();
    const pname = document.getElementById('machineName').value;
    const aname = document.getElementById('axisName').value;
    const date = document.getElementById('projectDate').value;

    const plans = book().curves.map((cv, i) => ({ cv, index: i, cmp: compute(cv) }));
    const okPlans = plans.filter(p => p.cmp.stage === 'ok');
    const badPlans = plans.filter(p => p.cmp.stage !== 'ok');

    let body = `<div class="import-step">
        <h4>导出报告（PDF 静态交付物）</h4>
        <p>包含：输入项、理论结果、推荐清单、选定型号参数、实际应用值、两张曲线图、余量结论（需求 3.3）。</p>
        <div class="rp-summary">本次导出 <b>${plans.length}</b> 条方案（${s.label}页签）：
          <b>${okPlans.length}</b> 条可计算${badPlans.length ? `，<span style="color:#b02a1e">${badPlans.length} 条无法计算（将跳过正文）</span>` : ''}。</div>
        <div class="row"><label>客户名称</label><div class="field-wrap"><input type="text" id="rp_customer" placeholder="可选"></div><span class="unit"></span></div>
        <div class="row"><label>工况说明</label><div class="field-wrap"><input type="text" id="rp_note" placeholder="可选"></div><span class="unit"></span></div>
        <div class="sub-label">以上文字随报告导出（需求 3.3）</div>
        <div class="sub-label">点击「导出 PDF」会打开打印预览：目标打印机选「另存为 PDF」后，可在保存对话框中选择存放位置。</div>
      </div>`;

    let previewHtml = '';
    if (!plans.length) {
      body += `<div class="error-text">当前页签下没有方案，无法生成报告</div>`;
    } else {
      const planSections = plans.map(p => {
        const cmp = p.cmp;
        const head = `<h4 class="rp-plan-head">方案 ${p.index + 1} / ${plans.length}</h4>`;
        if (cmp.stage !== 'ok') {
          return `<div class="report-preview plan">${head}
            <div class="error-text">该方案无法计算：${cmp.error || '输入不完整'}</div>
            <table>${kv('负载（' + s.loadLabel + '）', fmt(p.cv.load, 6) + ' ' + s.loadUnit)}
              ${kv('位移（' + s.distLabel + '）', fmt(p.cv.dist, 4) + ' ' + s.distUnit)}</table>
          </div>`;
        }
        return `<div class="report-preview plan">${head}${planBody(cmp, p.cv, s, p.index, plans.length)}</div>`;
      }).join('');

      previewHtml = `<div class="report-preview cover">
        <h3>电机选型报告</h3>
        <div style="text-align:center;color:#6b7683;font-size:10px;margin-bottom:8px">
          ${pname} · ${aname} · ${date} · ${s.label}
        </div>
        <table>
          ${kv('方案数量', plans.length + ' 条')}
          ${kv('可计算方案', okPlans.length + ' 条' + (badPlans.length ? `（${badPlans.length} 条跳过）` : ''))}
          ${kv('运动模式', state.mode === 'p2p' ? '点到点' : '自定义（上限判定）')}
          ${kv('S 曲线', state.smoothOn ? '开启，τ = ' + state.tau + ' ms' : '关闭')}
          ${kv('安全余量下限', state.marginLow + ' %')}
        </table>
        <table class="rp-plan-toc">
          ${plans.map(p => kv(`方案 ${p.index + 1}`,
            (p.cv.dist != null ? fmt(p.cv.dist, 4) + ' ' + s.distUnit + ' / ' + fmt(p.cv.time, 4) + ' s' : '未填写') +
            (p.cmp.stage === 'ok'
              ? ' · ' + (p.cmp.selected ? motorLabel(p.cmp.selected) : '未选电机')
              : ' · 无法计算'))).join('')}
        </table>
      </div>${planSections}`;
      body += previewHtml;
    }

    return {
      title: '导出报告',
      body,
      foot: '<button class="btn" id="modalOk2">取消</button><button class="btn primary" id="modalOk">导出 PDF</button>',
      after: () => {
        document.getElementById('modalOk2').onclick = closeModal;
        const okBtn = document.getElementById('modalOk');
        if (!okPlans.length) { okBtn.disabled = true; return; }
        okBtn.onclick = () => {
          closeModal();
          printReportPdf(pname, aname, date, s, plans, okPlans);
        };
      }
    };
  }

  /** 单条方案的报告正文（输入 / 理论 / 推荐 / 选定型号 / 实际应用值） */
  function planBody(cmp, cv, s, index, total) {
    const theo = cmp.theo;
    const passing = cmp.evals.filter(e => e.pass).slice(0, 4);
    const sel = cmp.selected;
    const ap = cmp.applied;
    return `
      <div class="rp-section"><div class="rp-title">一、输入工况</div>
        <table>
          ${kv('负载（' + s.loadLabel + '）', fmt(cv.load, 6) + ' ' + s.loadUnit)}
          ${kv('位移（' + s.distLabel + '）', fmt(cv.dist, 4) + ' ' + s.distUnit)}
          ${kv('运动时间', fmt(cv.time, 4) + ' s')}
          ${kv('停顿时间', fmt(cv.dwell, 4) + ' s')}
          ${kv('最大速度上限', isNum(cv.vMax) ? fmt(cv.vMax, 3) + ' ' + s.vUnit : '未设')}
          ${kv('最大加速度上限', isNum(cv.aMax) ? fmt(cv.aMax, 3) + ' ' + s.aUnit : '未设')}
          ${kv('阻力 / 力矩', fmt(cv.resist, 3) + ' ' + s.resistUnit)}
          ${kv('运动模式', state.mode === 'p2p' ? '点到点（加速:匀速:减速 = 1:1:1）' : '自定义（上限判定）')}
          ${kv('S 曲线', state.smoothOn ? '开启，τ = ' + state.tau + ' ms' : '关闭')}
        </table>
      </div>

      <div class="rp-section"><div class="rp-title">二、理论结果（仅含负载）</div>
        <table>
          ${kv('曲线形状', cmp.curve.shape === 'triangle' ? '三角形' : '梯形')}
          ${kv('峰值速度', fmt(C.speedDisplay(state.kind, cmp.curve.vPeak), 3) + ' ' + s.vUnit)}
          ${kv('峰值加速度', fmt(C.accelDisplay(state.kind, cmp.curve.aPeak), 3) + ' ' + s.aUnit + (cmp.curve.smoothApplied ? '（平滑后上抬）' : ''))}
          ${kv('加速 / 匀速 / 减速', fmt(cmp.curve.tAccel, 3) + ' / ' + fmt(Math.max(cmp.curve.tCruise, 0), 3) + ' / ' + fmt(cmp.curve.tDecel, 3) + ' s')}
          ${kv('总循环周期', fmt(theo.cycleTime, 3) + ' s')}
          ${kv('持续需求', fmt(theo.cont, 3) + ' ' + s.forceUnit)}
          ${kv('峰值需求', fmt(theo.peak, 3) + ' ' + s.forceUnit)}
        </table>
      </div>

      <div class="rp-section"><div class="rp-title">三、推荐清单（下限 ${state.marginLow}%）</div>
        ${passing.length ? `<table>
          ${passing.map(e => kv(motorLabel(e.motor), '余量 ' + fmt(e.headroom * 100, 0) + '%')).join('')}
        </table>` : `<div style="color:#b02a1e">当前无合规型号，建议放宽下限或补充电机库</div>`}
      </div>

      ${sel ? `<div class="rp-section"><div class="rp-title">四、选定型号：${motorLabel(sel)}（${D.MOTOR_TYPES[sel.type].label}）</div>
        <table>
          ${kv('结构 / 冷却', sel.structure + (sel.cooling ? ' / ' + sel.cooling : ''))}
          ${kv(s.kind === 'linear' ? '连续推力' : '连续力矩', fmt(s.kind === 'linear' ? sel.forceCont : sel.torqueCont, 3) + ' ' + s.forceUnit)}
          ${kv(s.kind === 'linear' ? '峰值推力' : '峰值力矩', fmt(s.kind === 'linear' ? sel.forcePeak : sel.torquePeak, 3) + ' ' + s.forceUnit)}
          ${kv('来源', sel.source.doc + ' · 第 ' + sel.source.page + ' 页 · ' + sel.source.date + ' · ' + sel.source.method)}
        </table></div>` : `<div class="rp-section"><div class="rp-title">四、选定型号</div>
        <div style="color:#6b7683">本方案未选定电机</div></div>`}

      ${ap ? `<div class="rp-section"><div class="rp-title">五、实际应用值</div>
        <table>
          ${kv('含电机惯量的峰值需求', fmt(ap.peakApplied, 3) + ' ' + s.forceUnit)}
          ${kv('连续电流 / 峰值电流', fmt(ap.iContRms, 3) + ' / ' + fmt(ap.iPeakRms, 3) + ' A')}
          ${kv('线圈温度', fmt(ap.tCoil, 1) + ' °C（容许 ' + fmt(ap.iAllow, 2) + ' A）')}
          ${kv('所需母线电压', fmt(ap.uReq, 1) + ' VDC' + (ap.supply ? ' · ' + ap.supply.text : ''))}
          ${kv('安全系数', fmt(ap.safety, 1) + ' %')}
          ${kv('热校核结论', ap.thermalPass === true ? '通过' : ap.thermalPass === false ? '超限' : '数据不足')}
        </table></div>` : ''}
    `;
  }

  /**
   * 导出 PDF：把报告渲染到临时 iframe 并调用打印，
   * 用户在打印对话框中选择「另存为 PDF」后，即可通过系统保存对话框选择存放位置。
   *
   * 每条方案各带自己的两张曲线图（离屏生成），
   * 不能再用屏幕上那张 canvas —— 那只是当前正在查看的方案（第 42 轮）。
   */
  function printReportPdf(pname, aname, date, s, plans, okPlans) {
    const docTitle = `${pname}-${aname}-电机选型报告`;
    const cssHref = new URL('css/style.css', window.location.href).href;

    // 逐方案生成正文 + 曲线图
    const planSections = plans.map(p => {
      const cmp = p.cmp;
      const head = `<h4 class="rp-plan-head">方案 ${p.index + 1} / ${plans.length}</h4>`;
      if (cmp.stage !== 'ok') {
        return `<div class="report-preview plan">${head}
          <div class="error-text">该方案无法计算：${cmp.error || '输入不完整'}</div></div>`;
      }
      const imgs = renderChartsToDataURI(cmp, p.cv);
      return `<div class="report-preview plan">
        ${head}
        ${planBody(cmp, p.cv, s, p.index, plans.length)}
        <div class="rp-section print-charts">
          <div class="rp-title">六、运动曲线</div>
          <img src="${imgs.speed}" alt="图1 速度-时间">
          <img src="${imgs.force}" alt="图2 力-时间">
        </div>
      </div>`;
    }).join('');

    const cover = `<div class="report-preview cover">
      <h3>电机选型报告</h3>
      <div style="text-align:center;color:#6b7683;font-size:10px;margin-bottom:8px">
        ${pname} · ${aname} · ${date} · ${s.label}
      </div>
      <table>
        ${kv('方案数量', plans.length + ' 条')}
        ${kv('可计算方案', okPlans.length + ' 条' + (plans.length - okPlans.length ? `（${plans.length - okPlans.length} 条跳过）` : ''))}
        ${kv('运动模式', state.mode === 'p2p' ? '点到点' : '自定义（上限判定）')}
        ${kv('S 曲线', state.smoothOn ? '开启，τ = ' + state.tau + ' ms' : '关闭')}
        ${kv('安全余量下限', state.marginLow + ' %')}
      </table>
      <table class="rp-plan-toc">
        ${plans.map(p => kv(`方案 ${p.index + 1}`,
          (p.cv.dist != null ? fmt(p.cv.dist, 4) + ' ' + s.distUnit + ' / ' + fmt(p.cv.time, 4) + ' s' : '未填写') +
          (p.cmp.stage === 'ok'
            ? ' · ' + (p.cmp.selected ? motorLabel(p.cmp.selected) : '未选电机')
            : ' · 无法计算'))).join('')}
      </table>
    </div>`;

    const html = `<!DOCTYPE html><html lang="zh-CN"><head>
      <meta charset="utf-8"><title>${shellEscape(docTitle)}</title>
      <link rel="stylesheet" href="${cssHref}">
      <style>
        body { background: #fff; }
        .report-preview { border: none; padding: 0; font-size: 12px; }
        .report-preview .rp-section { break-inside: avoid; }
        .report-preview h3 { font-size: 18px; }
        .rp-plan-head {
          font-size: 13px; color: #1f4e9c; margin: 14px 0 6px;
          padding-bottom: 3px; border-bottom: 2px solid #dbe8f6;
        }
        .report-preview.plan { break-before: page; }
        .print-charts img { width: 100%; margin-top: 6px; border: 1px solid #dfe5eb; }
        @page { size: A4; margin: 14mm; }
      </style></head><body>
      ${cover}
      ${planSections}
      <script>window.onload = function () { setTimeout(function () { window.focus(); window.print(); }, 250); };<\/script>
    </body></html>`;

    const frame = document.createElement('iframe');
    frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
    frame.setAttribute('aria-hidden', 'true');
    document.body.appendChild(frame);
    const doc = frame.contentDocument || frame.contentWindow.document;
    doc.open();
    doc.write(html);
    doc.close();
    setTimeout(() => frame.remove(), 45000);   // 打印对话框关闭后清理
  }

  function kv(k, v) { return `<tr><td>${k}</td><td>${v}</td></tr>`; }
  function shellEscape(s) { return s; }

  /* ---------------- 图表自适应 ---------------- */
  window.addEventListener('resize', () => renderCharts(compute()));

  /* ---------------- 启动 ---------------- */
  // 日期默认取今天（每次打开自动更新）
  const dateEl = document.getElementById('projectDate');
  if (dateEl && !dateEl.value) {
    const now = new Date();
    const pad = n => String(n).padStart(2, '0');
    dateEl.value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  }

  render();
})();

