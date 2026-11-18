package com.motorsizer.domain.catalog;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 派生项计算（设计轮 D17：派生项由服务端计算并在 C2 返回）。
 *
 * <p>公式与 {@code prototype/js/app.js:716-731} 保持一致，已由 4 份样册实测验证：
 * <ul>
 *   <li>{@code prated = iCont² × r25}</li>
 *   <li>{@code pmax = iPeak² × r25}</li>
 *   <li>三相（直线/力矩）：{@code kmDerived = kf|kt / √(1.5·r25)}；
 *       单相（音圈）：{@code kmDerived = kf / √r25}</li>
 * </ul>
 *
 * <p>这些值仅用于参数表展示，绝不参与准入、热校核或电压计算。
 */
public final class MotorDerivedCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int SCALE = 6;

    private MotorDerivedCalculator() {
    }

    public static MotorDerived compute(MotorType type, Map<String, BigDecimal> spec) {
        if (type == null || spec == null) {
            return new MotorDerived(null, null, null);
        }
        return new MotorDerived(
                ratedPower(spec),
                maxPower(spec),
                motorConstant(type, spec));
    }

    private static BigDecimal ratedPower(Map<String, BigDecimal> spec) {
        BigDecimal iCont = spec.get("iCont");
        BigDecimal r25 = spec.get("r25");
        if (iCont == null || r25 == null) {
            return null;
        }
        return iCont.pow(2).multiply(r25).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxPower(Map<String, BigDecimal> spec) {
        BigDecimal iPeak = spec.get("iPeak");
        BigDecimal r25 = spec.get("r25");
        if (iPeak == null || r25 == null) {
            return null;
        }
        return iPeak.pow(2).multiply(r25).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal motorConstant(MotorType type, Map<String, BigDecimal> spec) {
        // 直线与音圈使用力常数 kf，力矩使用转矩常数 kt
        BigDecimal kt = type == MotorType.TORQUE ? spec.get("kt") : spec.get("kf");
        BigDecimal r25 = spec.get("r25");
        if (kt == null || r25 == null || r25.signum() <= 0) {
            return null;
        }
        // 音圈为单相，无三相铜损的 1.5 系数
        BigDecimal copperFactor = type == MotorType.COIL ? BigDecimal.ONE : new BigDecimal("1.5");
        double root = Math.sqrt(copperFactor.multiply(r25, MC).doubleValue());
        if (root <= 0) {
            return null;
        }
        return kt.divide(BigDecimal.valueOf(root), MC).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
