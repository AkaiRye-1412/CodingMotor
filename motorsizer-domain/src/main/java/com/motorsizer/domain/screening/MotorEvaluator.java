package com.motorsizer.domain.screening;

import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorFieldGroup;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.MotorEvaluation;
import com.motorsizer.domain.model.MotorMissingField;
import com.motorsizer.domain.model.MotorParameters;
import com.motorsizer.domain.model.TheoreticalResult;
import com.motorsizer.domain.model.Verdict;
import com.motorsizer.domain.units.UnitConversions;
import com.motorsizer.domain.units.ValueFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * K1 准入判定（需求 7.1 / 7.2 / 第 43/44 轮）。
 *
 * <p>必填口径直接读取 K2 模板的 {@code required} 标记（C-B：入库门槛 = 准入门槛），
 * 不在本类硬编码字段清单。
 */
public final class MotorEvaluator {

    private MotorEvaluator() {
    }

    /** 参与判定所缺的必填字段；顺序与 K2 模板一致。 */
    public static List<MotorMissingField> requiredMissing(MotorParameters motor) {
        MotorTypeTemplate template = MotorCatalogTemplates.of(motor.type());
        List<MotorMissingField> missing = new ArrayList<>();

        for (MotorFieldGroup group : template.groups()) {
            for (MotorField field : group.fields()) {
                if (field.required() && !motor.has(field.key())) {
                    missing.add(new MotorMissingField(field.key(), field.label()));
                }
            }
        }
        return missing;
    }

    /**
     * 判定一台电机是否通过两条准入，并给出余量与可解释原因。
     *
     * @param marginLow 安全余量下限（如 0.3 表示 30%）
     */
    public static MotorEvaluation evaluate(
            AxisKind kind,
            MotorParameters motor,
            TheoreticalResult theoretical,
            MotionCurve curve,
            double marginLow) {

        List<MotorMissingField> missing = requiredMissing(motor);
        if (!missing.isEmpty()) {
            String labels = missing.stream()
                    .map(MotorMissingField::label)
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
            return new MotorEvaluation(
                    Verdict.UNKNOWN,
                    false,
                    null, null, null, null,
                    List.of(),
                    List.of("信息不全，无法判定：缺 " + labels),
                    null, null, null, null,
                    null, null,
                    missing);
        }

        Double contHaveValue = motor.value(kind == AxisKind.LINEAR ? "forceCont" : "torqueCont");
        Double peakHaveValue = motor.value(kind == AxisKind.LINEAR ? "forcePeak" : "torquePeak");
        if (contHaveValue == null || peakHaveValue == null) {
            // 防御：电机类型与页签不匹配时不应进入判定（应用层负责筛选候选池）
            return new MotorEvaluation(
                    Verdict.UNKNOWN,
                    false,
                    null, null, null, null,
                    List.of(),
                    List.of("电机参数与页签类型不匹配，无法判定"),
                    null, null, null, null,
                    null, null,
                    List.of());
        }

        double contHave = contHaveValue;
        double peakHave = peakHaveValue;
        double contNeed = theoretical.cont() * (1 + marginLow);
        double peakNeed = theoretical.peak() * (1 + marginLow);

        boolean passCont = contHave >= contNeed;
        boolean passPeak = peakHave >= peakNeed;

        // 速度比对需换算到电机库单位：旋转 rpm，直线 m/s
        double velocityDisplay = UnitConversions.speedToDisplay(kind, curve.vPeak());
        String velocityUnit = kind == AxisKind.LINEAR ? "m/s" : "rpm";

        boolean hasSpeedPeak = motor.has("maxSpeedPeak");
        boolean hasSpeedCont = motor.has("maxSpeedCont");
        Double maxSpeedPeak = motor.value("maxSpeedPeak");
        Double maxSpeedCont = motor.value("maxSpeedCont");

        boolean passSpeedPeak = !hasSpeedPeak || velocityDisplay <= maxSpeedPeak;
        boolean passSpeedCont = !hasSpeedCont || velocityDisplay <= maxSpeedCont;

        List<String> speedSkipped = new ArrayList<>();
        if (!hasSpeedPeak) {
            speedSkipped.add("最高速度 @峰值");
        }
        if (!hasSpeedCont) {
            speedSkipped.add("最高速度 @持续");
        }

        List<String> reasons = new ArrayList<>();
        if (passCont) {
            reasons.add("连续能力 ✓ " + ValueFormatter.format(contHave, 2)
                    + " ≥ 需求 " + ValueFormatter.format(contNeed, 2));
        } else {
            reasons.add("连续能力 ✗ " + ValueFormatter.format(contHave, 2)
                    + " < 需求 " + ValueFormatter.format(contNeed, 2)
                    + "（含下限 " + percent(marginLow) + "%）");
        }

        if (passPeak) {
            reasons.add("峰值能力 ✓ " + ValueFormatter.format(peakHave, 2)
                    + " ≥ 需求 " + ValueFormatter.format(peakNeed, 2));
        } else {
            reasons.add("峰值能力 ✗ " + ValueFormatter.format(peakHave, 2)
                    + " < 需求 " + ValueFormatter.format(peakNeed, 2));
        }

        if (!hasSpeedPeak) {
            reasons.add("峰值速度 " + ValueFormatter.format(velocityDisplay, 2)
                    + " " + velocityUnit + " 未校核（未录入最高速度 @峰值）");
        } else if (!passSpeedPeak) {
            reasons.add("峰值速度 ✗ " + ValueFormatter.format(velocityDisplay, 2)
                    + " " + velocityUnit + " > 电机 @峰值上限 "
                    + ValueFormatter.format(maxSpeedPeak, 2));
        } else {
            reasons.add("峰值速度 ✓ " + ValueFormatter.format(velocityDisplay, 2)
                    + " " + velocityUnit + " ≤ @峰值上限 "
                    + ValueFormatter.format(maxSpeedPeak, 2));
        }

        if (!hasSpeedCont) {
            reasons.add("峰值速度未校核（未录入最高速度 @持续）");
        } else if (!passSpeedCont) {
            reasons.add("峰值速度 ✗ " + ValueFormatter.format(velocityDisplay, 2)
                    + " " + velocityUnit + " > 电机 @持续上限 "
                    + ValueFormatter.format(maxSpeedCont, 2));
        } else {
            reasons.add("峰值速度 ✓ " + ValueFormatter.format(velocityDisplay, 2)
                    + " " + velocityUnit + " ≤ @持续上限 "
                    + ValueFormatter.format(maxSpeedCont, 2));
        }

        boolean pass = passCont && passPeak && passSpeedPeak && passSpeedCont;

        // 列表 Safety = 电机连续能力 / (理论连续需求 × (1 + 下限)) - 1
        double headroom = theoretical.cont() > 0
                ? (contHave / (theoretical.cont() * (1 + marginLow))) - 1
                : 0;
        double overSize = theoretical.cont() > 0
                ? (contHave / theoretical.cont()) - 1
                : 0;

        return new MotorEvaluation(
                pass ? Verdict.PASS : Verdict.FAIL,
                pass,
                passCont,
                passPeak,
                passSpeedPeak,
                passSpeedCont,
                speedSkipped,
                reasons,
                headroom,
                overSize,
                contHave,
                peakHave,
                contNeed,
                peakNeed,
                List.of());
    }

    /** 百分比文案：复刻 JS 数值直接拼接（30%，而不是 Java 的 30.0%）。 */
    private static String percent(double ratio) {
        double value = ratio * 100;
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
