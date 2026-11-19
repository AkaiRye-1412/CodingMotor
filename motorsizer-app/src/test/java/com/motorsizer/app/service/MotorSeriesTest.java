package com.motorsizer.app.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个页签的「系列」筛选（第 67/68 轮）：型号名前缀匹配 + 「其他」排除法。
 */
class MotorSeriesTest {

    private static final List<String> ROTARY = MotorSeries.ROTARY_SERIES;
    private static final List<String> LINEAR = MotorSeries.LINEAR_SERIES;

    @Test
    void matchesKnownRotaryPrefixIgnoringCase() {
        assertThat(MotorSeries.matches("ADR175-A102-S", "ADR", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("adr175-a102-s", "ADR", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("ACD85-P-15-Y", "ACD", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("AXD120-71-S", "AXD", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("ACW-20", "ACW", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("ACR-33", "ACR", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("AMR150-A", "AMR", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("AXM40-43-S", "AXM", ROTARY)).isTrue();
    }

    @Test
    void matchesKnownLinearPrefix() {
        assertThat(MotorSeries.matches("ACM100-A", "ACM", LINEAR)).isTrue();
        assertThat(MotorSeries.matches("AKM200-B4", "AKM", LINEAR)).isTrue();
        assertThat(MotorSeries.matches("AKH-7", "AKH", LINEAR)).isTrue();
        assertThat(MotorSeries.matches("AVM120", "AVM", LINEAR)).isTrue();
        assertThat(MotorSeries.matches("AVA80", "AVA", LINEAR)).isTrue();
        assertThat(MotorSeries.matches("AUM3", "AUM", LINEAR)).isTrue();
    }

    @Test
    void knownPrefixDoesNotMatchWrongSeries() {
        assertThat(MotorSeries.matches("ADR175-A102-S", "ACD", ROTARY)).isFalse();
        assertThat(MotorSeries.matches("AXD120-71-S", "ACR", ROTARY)).isFalse();
        assertThat(MotorSeries.matches("AKM200-B4", "ACM", LINEAR)).isFalse();
    }

    @Test
    void otherUsesExclusionPerAxis() {
        assertThat(MotorSeries.matches("XYZ-1", "其他", ROTARY)).isTrue();
        assertThat(MotorSeries.matches("ADR175-A102-S", "其他", ROTARY)).isFalse();
        assertThat(MotorSeries.matches("AMR150-A", "其他", ROTARY)).isFalse();
        assertThat(MotorSeries.matches("AKM200-B4", "其他", LINEAR)).isFalse();
        assertThat(MotorSeries.matches("ACM100-A", "其他", LINEAR)).isFalse();
        assertThat(MotorSeries.matches("FOO-BAR", "其他", LINEAR)).isTrue();
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(MotorSeries.matches(null, "ADR", ROTARY)).isFalse();
        assertThat(MotorSeries.matches("ADR1", null, ROTARY)).isFalse();
        assertThat(MotorSeries.matches("ADR1", "  ", ROTARY)).isFalse();
    }
}
