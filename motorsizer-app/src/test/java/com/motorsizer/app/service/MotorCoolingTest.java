package com.motorsizer.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 「结构」（冷却方式）判定口径——第 69 轮。
 *
 * <p>判据是三级：型号结尾 {@code -A/-W} → 库里冷却字段 → 型号中间段兜底。
 * 中间的 {@code -A-} 可能是结构代号（真库 {@code RDM020-A-B2}），所以不能一见到就认气冷。
 */
class MotorCoolingTest {

    @Test
    void suffixADeclaresAirEvenIfFieldSaysNormal() {
        // AUM4-S1-S-A 是样册拆出来的气冷变体；字段万一没跟上，以型号结尾为准
        assertThat(MotorCooling.of("AUM4-S1-S-A", "普通")).isEqualTo(MotorCooling.AIR);
    }

    @Test
    void suffixWDeclaresWaterEvenIfFieldIsEmpty() {
        assertThat(MotorCooling.of("AUM4-S1-S-W", null)).isEqualTo(MotorCooling.WATER);
    }

    @Test
    void structuralASegmentIsNotAir() {
        // -A- 是「A 型」结构代号，后面还跟着 -B2 规格段；字段写的是普通
        assertThat(MotorCooling.of("RDM020-A-B2", "普通")).isEqualTo(MotorCooling.NATURAL);
        assertThat(MotorCooling.of("RDM060-A-B6", "普通")).isEqualTo(MotorCooling.NATURAL);
    }

    @Test
    void waterSegmentInMiddleIsWaterWithoutField() {
        // AKM100-W-B2-S 结尾不是 -W，靠中间段兜底；字段为空时也要判对
        assertThat(MotorCooling.of("AKM100-W-B2-S", "")).isEqualTo(MotorCooling.WATER);
    }

    @Test
    void ordinaryFieldIsNatural() {
        assertThat(MotorCooling.of("ALM-40-20", "普通")).isEqualTo(MotorCooling.NATURAL);
        assertThat(MotorCooling.of("ALM-40-20", "自然冷却")).isEqualTo(MotorCooling.NATURAL);
    }

    @Test
    void modelWithoutAnyMarkerIsNatural() {
        assertThat(MotorCooling.of("ACD85-P-15-Y", "普通")).isEqualTo(MotorCooling.NATURAL);
        assertThat(MotorCooling.of(null, null)).isEqualTo(MotorCooling.NATURAL);
        assertThat(MotorCooling.of("  ", "  ")).isEqualTo(MotorCooling.NATURAL);
    }

    @Test
    void suffixMatchingIgnoresCase() {
        assertThat(MotorCooling.of("aum4-s1-s-a", null)).isEqualTo(MotorCooling.AIR);
        assertThat(MotorCooling.of("aum4-s1-s-w", null)).isEqualTo(MotorCooling.WATER);
    }
}
