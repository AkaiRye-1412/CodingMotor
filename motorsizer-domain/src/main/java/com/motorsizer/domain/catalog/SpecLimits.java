package com.motorsizer.domain.catalog;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 规格字段的业务范围（设计轮 D18 Q5=A，04 §1.6）。
 *
 * <p>这些上限集中定义在 domain，不散落在 Controller；预检优于让 DECIMAL(18,6) 溢出报库错误。
 * 未列出的字段不做业务上限（如 {@code km}、{@code te}、{@code magForce}）。
 */
public final class SpecLimits {

    private static final BigDecimal LARGE = new BigDecimal("100000");
    private static final BigDecimal FORCE_CONST = new BigDecimal("1000");
    private static final BigDecimal RESISTANCE = new BigDecimal("10000");
    private static final BigDecimal CURRENT = new BigDecimal("1000");
    private static final BigDecimal TEMPERATURE = new BigDecimal("300");
    private static final BigDecimal HEAT = new BigDecimal("1000");
    private static final BigDecimal VOLTAGE = new BigDecimal("5000");
    private static final BigDecimal INDUCTANCE = new BigDecimal("100000");
    private static final BigDecimal MASS = new BigDecimal("10000");
    private static final BigDecimal INERTIA = new BigDecimal("1000");
    private static final BigDecimal LENGTH = new BigDecimal("10000");

    private static final Map<String, BigDecimal> MAX = Map.ofEntries(
            Map.entry("forceCont", LARGE),
            Map.entry("forcePeak", LARGE),
            Map.entry("torqueCont", LARGE),
            Map.entry("torquePeak", LARGE),
            Map.entry("kf", FORCE_CONST),
            Map.entry("kt", FORCE_CONST),
            Map.entry("ke", FORCE_CONST),
            Map.entry("r25", RESISTANCE),
            Map.entry("iCont", CURRENT),
            Map.entry("iPeak", CURRENT),
            Map.entry("tMax", TEMPERATURE),
            Map.entry("kth", HEAT),
            Map.entry("uBus", VOLTAGE),
            Map.entry("maxSpeedPeak", LARGE),
            Map.entry("maxSpeedCont", LARGE),
            Map.entry("l", INDUCTANCE),
            Map.entry("totalMass", MASS),
            Map.entry("moverMass", MASS),
            Map.entry("coreMass", MASS),
            Map.entry("trackMass", MASS),
            Map.entry("inertia", INERTIA),
            Map.entry("stroke", LENGTH),
            Map.entry("coilLength", LENGTH),
            Map.entry("gap", LENGTH),
            Map.entry("polePitch", LENGTH)
    );

    /**
     * 必须严格大于 0 的参数（04 §1.6 下限口径：推力、电阻、电流、热耗散、电压、温度）。
     */
    private static final Set<String> POSITIVE = Set.of(
            "forceCont", "forcePeak", "torqueCont", "torquePeak",
            "r25", "iCont", "iPeak", "kth", "uBus", "tMax"
    );

    private SpecLimits() {
    }

    public static Optional<BigDecimal> maxOf(String key) {
        return Optional.ofNullable(MAX.get(key));
    }

    public static boolean mustBePositive(String key) {
        return POSITIVE.contains(key);
    }
}
