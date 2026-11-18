/* 原型自检脚本（node 运行，非页面依赖）
 * 校验 calc.js 的核心公式，对照需求文档中的数值：
 *   直线点到点 S=0.3 / T=0.5 → v=0.9, a=5.4（需求第 6 轮表）
 *   S 曲线 τ=16ms → a 上抬 10.6%
 *   ADR110-B136 串联 → 环境 22.5°C 时 I_allow ≈ 2.993 A（需求 5.3 自洽验证）
 */
global.window = global;
require('./js/data.js');
require('./js/calc.js');
const C = global.CALC;
const D = global.APP_DATA;

let fails = 0;
function check(name, actual, expect, tol) {
  const ok = Math.abs(actual - expect) <= (tol == null ? 1e-6 : tol);
  if (!ok) fails++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${actual}  (期望 ${expect})`);
}

console.log('=== 点到点闭式公式 ===');
const p2p = C.planPointToPoint({ dist: 0.3, time: 0.5 });
check('vPeak = 1.5S/T', p2p.vPeak, 0.9, 1e-9);
check('aPeak = 4.5S/T²', p2p.aPeak, 5.4, 1e-9);
check('tAccel = T/3', p2p.tAccel, 0.5 / 3, 1e-9);
check('tCruise = T/3', p2p.tCruise, 0.5 / 3, 1e-9);

console.log('\n=== S 曲线：v 不变、a 上抬 ===');
[4, 16, 56].forEach(tau => {
  const s = C.applySmooth(p2p, tau);
  check(`τ=${tau}ms 可行`, s.ok ? 1 : 0, 1);
  check(`τ=${tau}ms v 不变`, s.curve.vPeak, 0.9, 1e-9);
  console.log(`       τ=${tau}ms → a=${C.fmt(s.curve.aPeak, 3)}, 上抬=${C.fmt((s.curve.aPeak / 5.4 - 1) * 100, 1)}%`);
});
const lift16 = C.applySmooth(p2p, 16).curve.aPeak / 5.4 - 1;
check('τ=16ms 上抬约 10.6%', lift16 * 100, 10.6, 0.5);
const lift56 = C.applySmooth(p2p, 56).curve.aPeak / 5.4 - 1;
check('τ=56ms 上抬约 50.6%', lift56 * 100, 50.6, 0.5);

console.log('\n=== S 曲线可行域 T ≥ 6τ ===');
const shortCv = C.planPointToPoint({ dist: 0.1, time: 0.2 });     // x=66.7ms, 6τ(56)=336ms > 200ms
const bad = C.applySmooth(shortCv, 56);
check('T=0.2s / τ=56ms 不放行', bad.ok ? 0 : 1, 1);
console.log(`       ${bad.message.replace(/\n/g, ' | ')}`);

console.log('\n=== 形状是用户输入（点到点）===');
const p2pTri = C.planPointToPoint({ dist: 0.3, time: 0.5, shape: 'triangle' });
check('点到点选三角形 → shape', p2pTri.shape === 'triangle' ? 1 : 0, 1);
check('三角形 x = T/2', p2pTri.x, 0.25, 1e-9);
check('三角形 v = 2S/T', p2pTri.vPeak, 1.2, 1e-9);
check('三角形 a = 4S/T²', p2pTri.aPeak, 4.8, 1e-9);
check('三角形匀速时间 = 0', p2pTri.tCruise, 0, 1e-9);
const p2pTra = C.planPointToPoint({ dist: 0.3, time: 0.5, shape: 'trapezoid' });
check('点到点选梯形 x = T/3', p2pTra.x, 0.5 / 3, 1e-9);

console.log('\n=== 自定义：形状自动判定（第 26 轮）===');

// 图9：S=1, T=1, a=4, v=2 → a·x(1−x)=1 → x=0.5（三角形）
const c1 = C.planCustom({ dist: 1, time: 1, aMax: 4, vMax: 2 });
check('图9 求解成功', c1.ok ? 1 : 0, 1);
check('图9 判定为三角形', c1.shape === 'triangle' ? 1 : 0, 1);
check('图9 x = 0.5', c1.x, 0.5, 1e-9);
check('图9 vPeak = 2', c1.vPeak, 2, 1e-9);
check('图9 aPeak = 4', c1.aPeak, 4, 1e-9);
check('图9 匀速时间 = 0', c1.tCruise, 0, 1e-9);

// 图10：S=2, T=1, a=9, v=3 → a·x(1−x)=2 → x=1/3（梯形）
const c2 = C.planCustom({ dist: 2, time: 1, aMax: 9, vMax: 3 });
check('图10 求解成功', c2.ok ? 1 : 0, 1);
check('图10 判定为梯形', c2.shape === 'trapezoid' ? 1 : 0, 1);
check('图10 x = 1/3', c2.x, 1 / 3, 1e-9);
check('图10 vPeak = 3', c2.vPeak, 3, 1e-9);
check('图10 aPeak = 9', c2.aPeak, 9, 1e-9);
check('图10 匀速时间 = 1/3', c2.tCruise, 1 / 3, 1e-9);

// 图11：S=2, T=1, a=10 → x = (10−√(100−80))/20 = 0.27639，梯形；v 自动反算
const c3 = C.planCustom({ dist: 2, time: 1, aMax: 10 });
check('图11 求解成功', c3.ok ? 1 : 0, 1);
check('图11 判定为梯形', c3.shape === 'trapezoid' ? 1 : 0, 1);
check('图11 x = (aT−√(a²T²−4aS))/(2a)', c3.x, (10 - Math.sqrt(100 - 80)) / 20, 1e-9);
check('图11 aPeak = 10', c3.aPeak, 10, 1e-9);
check('图11 vPeak 自动反算', c3.vPeak, 2 / (1 - c3.x), 1e-9);

console.log('\n=== 自定义：缺项自动反算 ===');
const onlyT = C.planCustom({ dist: 0.3, time: 0.5 });
check('只给 S+T 可用（默认 1:3 梯形）', onlyT.ok ? 1 : 0, 1);
check('  默认 x = T/3', onlyT.x, 0.5 / 3, 1e-9);

const noT = C.planCustom({ dist: 1, vMax: 2, aMax: 4 });
check('无时间、速度+加速度 → 反算时间', noT.ok ? 1 : 0, 1);
check('  反算 T = S/v + v/a', noT.time, 1 / 2 + 2 / 4, 1e-9);

const onlyA = C.planCustom({ dist: 1, aMax: 4 });
check('仅 S+a → 可解（三角形）', onlyA.ok ? 1 : 0, 1);
check('  T = 2√(S/a)', onlyA.time, 2 * Math.sqrt(0.25), 1e-9);
check('  判定为三角形', onlyA.shape === 'triangle' ? 1 : 0, 1);

const onlyV = C.planCustom({ dist: 1, vMax: 2 });
check('仅 S+v → 报错（无法定位形）', onlyV.ok ? 0 : 1, 1);

console.log('\n=== 自定义：低速度上限对应合法梯形 ===');
// vMax=1.9 < 2S/T=2：不是错误，而是更低的峰值速度 + 更高的加速度
const vLow = C.planCustom({ dist: 1, time: 1, vMax: 1.9 });
check('vMax 低于 2S/T 仍可解（梯形）', vLow.ok ? 1 : 0, 1);
check('  vPeak = vMax', vLow.vPeak, 1.9, 1e-9);
check('  加速度相应升高', vLow.aPeak, 1 / (vLow.x * (1 - vLow.x)), 1e-9);
check('  aPeak > 4S/T²', vLow.aPeak > 4 ? 1 : 0, 1);

console.log('\n=== 自定义：冲突报错 ===');
const aLow = C.planCustom({ dist: 1, time: 1, aMax: 3.9 });
check('aMax 低于 4S/T² → 报错', aLow.ok ? 0 : 1, 1);
const vTooLow = C.planCustom({ dist: 1, time: 1, vMax: 0.9 });
check('vMax 低于 S/T（匀速都走不完）→ 报错', vTooLow.ok ? 0 : 1, 1);
const bothBad = C.planCustom({ dist: 1, time: 1, aMax: 5, vMax: 1.2 });
check('速度与加速度互相矛盾 → 报错', bothBad.ok ? 0 : 1, 1);

console.log('\n=== 旋转单位换算 ===');
check('1 rpm = 6 °/s', C.speedDisplay('rotary', 6), 1, 1e-9);
check('°/s² → rad/s²', C.accelFactor('rotary'), Math.PI / 180, 1e-12);
// θ=180°, T=0.5s（点到点）→ v_peak=1.5*180/0.5=540 °/s=90 rpm
const rot = C.planPointToPoint({ dist: 180, time: 0.5 });
check('旋转 vPeak(°/s)', rot.vPeak, 540, 1e-9);
check('旋转 vPeak(rpm)', C.speedDisplay('rotary', rot.vPeak), 90, 1e-9);

console.log('\n=== 热模型自洽性（需求 5.3）===');
const adr = D.motors.rotary.find(m => m.id === 'ADR110-B136 / 串联');
const tAmb = 22.5, Tmax = adr.Tmax, R25 = adr.R25, Kth = adr.Kth;
const rHot = R25 * (1 + C.ALPHA_CU * (Tmax - 25));
console.log(`       R_hot=${C.fmt(rHot, 3)} Ω`);
const iAllow = Math.sqrt(Kth * (Tmax - tAmb) / (1.5 * rHot));
check('I_allow ≈ 样册 3.0 Arms', iAllow, 3.0, 0.02);

console.log('\n=== 重力分量（对齐原型软件数值）===');
// m=8, S=1, T=1（点到点梯形）, θ=90°, 方向=上 → 原型软件：峰值 114.480 N / 持续 83.804 N
const gCv = C.planPointToPoint({ dist: 1, time: 1, shape: 'trapezoid' });
const gTheo = C.theoretical('linear', 8, gCv, 0, { dwell: 0, tilt: 90, direction: 'up' });
check('重力分量 = m·g·sin90°', gTheo.gravity, 8 * 9.81, 1e-6);
check('峰值推力 114.480', gTheo.peak, 114.48, 0.01);
check('持续推力 83.804（循环 RMS）', gTheo.cont, 83.804, 0.01);
check('匀速段 = 重力 + 阻力', gTheo.cruise, 78.48, 0.01);
const noTilt = C.theoretical('linear', 8, gCv, 0, { dwell: 0 });
check('θ=0 时无重力项', noTilt.gravity, 0, 1e-12);
check('θ=0 峰值 36', noTilt.peak, 36, 0.01);
// 方向=下 → 倾斜角 270°（sin270° = −1），重力反向
const downDir = C.theoretical('linear', 8, gCv, 0, { dwell: 0, tilt: 270, direction: 'down' });
check('方向=下（θ=270°）→ 重力反向', downDir.gravity, -78.48, 0.01);
check('  下降时峰值 = m·a − G', downDir.peak, 36 - 78.48, 0.01);
const rotG = C.theoretical('rotary', 0.05, C.planPointToPoint({ dist: 360, time: 0.6 }), 0.5,
  { dwell: 0, tilt: 90, direction: 'up' });
check('旋转电机无重力分量', rotG.gravity, 0, 1e-12);

console.log('\n=== 准入判定 ===');
const theo = C.theoretical('rotary', 0.0008, rot, 0, { dwell: 0.3 });
console.log(`       理论：峰值=${C.fmt(theo.peak, 3)} N·m，持续=${C.fmt(theo.cont, 3)} N·m`);
const ev = C.evaluateMotor('rotary', adr, theo, rot, 0.3);
console.log(`       ADR110 串联 → ${ev.verdict}：${ev.reasons.join(' / ')}`);
check('判定结果存在', ev.verdict ? 1 : 0, 1);

const missingMotor = D.motors.linear.find(m => m.id === 'ILM-20-050');
const ev2 = C.evaluateMotor('linear', missingMotor, C.theoretical('linear', 12, p2p, 0, { dwell: 0.3 }), p2p, 0.3);
check('缺 Kth/Tmax → unknown', ev2.verdict === 'unknown' ? 1 : 0, 1);
console.log(`       ${ev2.reasons[0]}`);

console.log('\n=== 实际应用值 ===');
const app = C.appliedValue('rotary', adr, theo, rot, {
  load: 0.0008, resist: 0, dwell: 0.3, tAmbient: 22, uSupply: 311, marginLow: 0.3
});
console.log(`       所需母线=${C.fmt(app.uReq, 1)} VDC，线圈温度=${C.fmt(app.tCoil, 1)} °C，I_cont=${C.fmt(app.iContRms, 3)} A`);
check('所需母线电压已算出', app.uReq > 0 ? 1 : 0, 1);
check('供电结论存在', app.supply ? 1 : 0, 1);

console.log(`\n${fails === 0 ? '✅ 全部通过' : '❌ 失败 ' + fails + ' 项'}`);
process.exitCode = fails === 0 ? 0 : 1;

