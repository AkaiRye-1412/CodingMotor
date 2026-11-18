package com.motorsizer.domain.model;

/** 供电结论（需求 7.4）：所需母线电压与用户供电能力的比较。 */
public record SupplyVerdict(
        double have,
        double need,
        double remain,
        double rate,
        boolean pass,
        String text) {
}
