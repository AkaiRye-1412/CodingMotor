package com.motorsizer.domain.physics;

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
 * <p>逐行等价于原型 {@code calc.js:appliedValue}（第 45 轮口径：不叠加电机自身质量/惯量）。
 */
public final class AppliedCalculator {

    /** 铜电阻温度系数（/K），与原型一致。 */
    public static final double ALPHA_CU = 0.00393;

    private static final double DEFAULT_AMBIENT_TEMP = 22.0;
    private static final double SQRT_2 = Math.sqrt(2.0);

    private AppliedCalculator() {
    }

    public static AppliedResult applied(
            AxisKind kind,
            MotorParameters motor,
            TheoreticalResult theoretical,
            MotionCurve curve,
            AppliedEnvironment env) {

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
        Double r25 = motor.value("r25");
        Double kth = motor.value("kth");

        Double rHot = isFinite(r25) && isFinite(tMax)
                ? r25 * (1 + ALPHA_CU * (tMax - 25))
                : null;

        Double iAllow = null;
        if (isFinite(rHot) && isFinite(kth) && isFinite(tMax)) {
            iAllow = Math.sqrt(Math.max(kth * (tMax - ambientTemp), 0) / (1.5 * rHot));
        }

        Double tCoil = null;
        if (isFinite(rHot) && isFinite(kth) && isFinite(iContRms)) {
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

        Double safety = contApplied > 0 && contHave != null
                ? ((contHave - contApplied) / contApplied) * 100
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
