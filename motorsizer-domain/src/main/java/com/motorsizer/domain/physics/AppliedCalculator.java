package com.motorsizer.domain.physics;

import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.model.AppliedEnvironment;
import com.motorsizer.domain.model.AppliedResult;
import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.MotorParameters;
import com.motorsizer.domain.model.SupplyVerdict;
import com.motorsizer.domain.model.TheoreticalResult;
import com.motorsizer.domain.model.Verdict;
import com.motorsizer.domain.units.UnitConversions;
import com.motorsizer.domain.units.ValueFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * K1 实际应用值：电流、热校核、母线电压、余量与供电结论。
 *
 * <p>逐行等价于原型 {@code calc.js:appliedValue}；{@code env.load()} 传的是**总负载**
 * （用户负载 + 选中电机自身质量/惯量，CAL-26；第 45 轮曾为纯负载）。
 *
 * <p>唯一不照抄原型的是安全余量：2026-11-21 起改为与列表 Safety 同口径（扣过余量下限），
 * 见 {@link #applied} 内的注释。原型是「(能力 − 需求) ÷ 需求」的纯物理富余。
 */
public final class AppliedCalculator {

    /** 铜电阻温度系数（/K），与原型一致。 */
    public static final double ALPHA_CU = 0.00393;

    private static final double DEFAULT_AMBIENT_TEMP = 22.0;
    private static final double SQRT_2 = Math.sqrt(2.0);

    private AppliedCalculator() {
    }

    /**
     * @param marginLow 安全余量下限（0.3 表示 30%）：安全余量与之同口径，见下方 {@code safety}
     */
    public static AppliedResult applied(
            AxisKind kind,
            MotorParameters motor,
            TheoreticalResult theoretical,
            MotionCurve curve,
            AppliedEnvironment env,
            double marginLow) {

        double ambientTemp = env.ambientTemp() != null && Double.isFinite(env.ambientTemp())
                ? env.ambientTemp()
                : DEFAULT_AMBIENT_TEMP;

        double totalInertia = env.load();
        Double unitCurrent = kind == AxisKind.LINEAR ? motor.value("kf") : motor.value("kt");

        double accelTerm = totalInertia * curve.aPeak() * UnitConversions.accelerationFactor(kind);
        double gravity = ForceCalculator.gravityTerm(kind, totalInertia, env.tiltDeg());

        double peakApplied = accelTerm + env.resist() + gravity;
        double cruiseApplied = Math.abs(env.resist() + gravity);
        double decelApplied = Math.abs(accelTerm - env.resist() - gravity);
        double contApplied = ForceCalculator.rmsOfCycle(
                peakApplied, curve.tAccel(),
                cruiseApplied, curve.tCruise(),
                decelApplied, curve.tDecel(),
                env.dwell());

        // 原型用 truthiness：unitCurrent 为 null/0 时电流不可算
        Double iPeakRms = isNonZero(unitCurrent) ? peakApplied / unitCurrent : null;
        Double iContRms = isNonZero(unitCurrent) ? contApplied / unitCurrent : null;
        Double iPeakMax = isFinite(iPeakRms) ? iPeakRms * SQRT_2 : null;

        Double tMax = motor.value("tMax");
        if (!isFinite(tMax)) {
            // 样册采集口径里没有「最高线圈温度」这一行 → 缺省按 100 °C 校核（用户裁定）。
            // 手工录入时表单必填，走到这里的大多是样册入库的电机，或口径确定之前入库的老数据。
            tMax = MotorCatalogTemplates.DEFAULT_MAX_COIL_TEMP;
        }
        Double r25 = motor.value("r25");
        Double kth = motor.value("kth");

        // 热校核缺什么，逐项说清楚（前端与报告直接拿去显示）。
        // 原来的提示语是写死的「缺 热耗散常数 Kth」，可样册入库的电机往往缺的是别的项，
        // 用户照着提示去补永远补不对。tMax 已有缺省值，不再列入。
        List<String> thermalMissing = new ArrayList<>();
        if (!isFinite(r25)) {
            thermalMissing.add("相间电阻 R25");
        }
        if (!isFinite(kth)) {
            thermalMissing.add("热耗散常数 Kth");
        }
        if (!isFinite(unitCurrent)) {
            thermalMissing.add(kind == AxisKind.LINEAR ? "力常数 Kf" : "转矩常数 Kt");
        }

        Double rHot = isFinite(r25) && isFinite(tMax)
                ? r25 * (1 + ALPHA_CU * (tMax - 25))
                : null;

        Double iAllow = null;
        if (isFinite(rHot) && isFinite(kth) && isFinite(tMax)) {
            iAllow = Math.sqrt(Math.max(kth * (tMax - ambientTemp), 0) / (1.5 * rHot));
        }

        Double tCoil = null;
        // 线圈温度只用 r25 / kth / 实际电流就能迭代出来，**不需要 tMax**——
        // tMax 只用于「允许电流 iAllow」与超温判定。原守卫把 rHot（依赖 tMax）也带进来，
        // 结果样册入库的电机（tMax 未采集）连温度都显示成「—」，还错误地报「缺热耗散常数」。
        if (isFinite(r25) && isFinite(kth) && isFinite(iContRms)) {
            double temp = ambientTemp + 1.5 * iContRms * iContRms * r25 / kth;
            temp = ambientTemp
                    + 1.5 * iContRms * iContRms * (r25 * (1 + ALPHA_CU * (temp - 25))) / kth;
            tCoil = temp;
        }

        Boolean thermalPass = isFinite(iAllow) && isFinite(iContRms)
                ? iContRms <= iAllow
                : null;

        // 所需母线电压：相电压峰值，取“加速终点”与“匀速段”较大者
        Double uReq = null;
        Double uAtAccel = null;
        Double uAtCruise = null;
        Double rVolt = null;
        List<String> uMissing = new ArrayList<>();

        Double ke = motor.value("ke");
        if (!isFinite(ke)) {
            uMissing.add("反电动势常数 Ke");
        }
        if (!isFinite(r25)) {
            uMissing.add("相间电阻 R25");
        }
        if (!isFinite(unitCurrent)) {
            uMissing.add(kind == AxisKind.LINEAR ? "力常数 Kf" : "转矩常数 Kt");
        }

        if (uMissing.isEmpty()) {
            double tRef = isFinite(tCoil) ? tCoil : 25;
            rVolt = r25 * (1 + ALPHA_CU * (tRef - 25));
            double vMotor = UnitConversions.speedToDisplay(kind, curve.vPeak());

            // 原型中 null 参与乘法按 0 处理
            double peakCurrent = iPeakMax == null ? 0 : iPeakMax;
            uAtAccel = ke * vMotor + peakCurrent * rVolt;

            double iCruise = env.resist() / unitCurrent;
            uAtCruise = ke * vMotor + iCruise * rVolt;
            uReq = Math.max(uAtAccel, uAtCruise);
        }

        // 安全系数与负载系数（第 45 轮）
        Double contHave = motor.value(kind == AxisKind.LINEAR ? "forceCont" : "torqueCont");
        Double peakHave = motor.value(kind == AxisKind.LINEAR ? "forcePeak" : "torquePeak");

        // 安全余量与 K1 列表的 Safety 同口径（2026-11-21 用户裁定，取代第 45 轮的纯物理口径）：
        //   安全余量 = 持续能力 / (实际持续需求 × (1 + 下限)) − 1
        // 0 表示刚好卡在下限，负值表示低于下限。与 MotorEvaluator.headroom 逐字同一公式，
        // 因此「顶部指标卡 / 应用值面板 / 报告 / 推荐列表」四处永远是同一个数。
        Double safety = contApplied > 0 && contHave != null
                ? ((contHave / (contApplied * (1 + marginLow))) - 1) * 100
                : null;
        Double loadCont = isFinite(contHave) && contHave > 0
                ? (contApplied / contHave) * 100
                : null;
        Double loadPeak = isFinite(peakHave) && peakHave > 0
                ? (peakApplied / peakHave) * 100
                : null;

        SupplyVerdict supply = null;
        Double supplyVoltage = env.supplyVoltage();
        if (isFinite(supplyVoltage) && isFinite(uReq)) {
            double remain = supplyVoltage - uReq;
            double rate = uReq > 0 ? (remain / uReq) * 100 : 0;
            boolean pass = remain >= 0;
            String text = pass
                    ? "供电满足（余量 " + ValueFormatter.format(remain, 1) + " VDC）"
                    : "供电不足（缺 " + ValueFormatter.format(-remain, 1) + " VDC）";
            supply = new SupplyVerdict(supplyVoltage, uReq, remain, rate, pass, text);
        }

        Double uBus = motor.value("uBus");
        Boolean overUbus = isFinite(uBus) && isFinite(uReq) ? uReq > uBus : null;

        Verdict verdict = thermalPass == null
                ? Verdict.UNKNOWN
                : (thermalPass ? Verdict.PASS : Verdict.FAIL);

        return new AppliedResult(
                totalInertia,
                peakApplied,
                cruiseApplied,
                decelApplied,
                gravity,
                contApplied,
                iPeakRms,
                iContRms,
                iPeakMax,
                rHot,
                iAllow,
                tCoil,
                thermalPass,
                uReq,
                uAtAccel,
                uAtCruise,
                rVolt,
                uMissing,
                thermalMissing,
                safety,
                loadCont,
                loadPeak,
                supply,
                overUbus,
                verdict);
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static boolean isNonZero(Double value) {
        return value != null && Double.isFinite(value) && value != 0;
    }
}
