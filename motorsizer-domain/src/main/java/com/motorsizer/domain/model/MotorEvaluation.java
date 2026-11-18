package com.motorsizer.domain.model;

import java.util.List;

/**
 * 单台电机的准入判定结果（需求 7.1 / 7.2 / 第 43/44 轮）。
 *
 * <p>{@code UNKNOWN} 时只填充 {@code missing} 与 {@code reasons}；其余可空字段为 {@code null}。
 * {@code headroom} 是列表 Safety（已扣下限门槛），与 {@code AppliedResult.safety} 口径不同（C-C）。
 */
public record MotorEvaluation(
        Verdict verdict,
        boolean pass,
        Boolean passCont,
        Boolean passPeak,
        Boolean passSpeedPeak,
        Boolean passSpeedCont,
        List<String> speedSkipped,
        List<String> reasons,
        Double headroom,
        Double overSize,
        Double contHave,
        Double peakHave,
        Double contNeed,
        Double peakNeed,
        List<MotorMissingField> missing) {

    public MotorEvaluation {
        speedSkipped = List.copyOf(speedSkipped);
        reasons = List.copyOf(reasons);
        missing = List.copyOf(missing);
    }
}
