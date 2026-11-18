package com.motorsizer.domain.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K2 模板一致性（C-B 不变量）：必填数、字段数、唯一 key、factor 口径。
 */
class MotorCatalogTemplatesTest {

    @Test
    void requiredCountsMatchDatabaseNotNullColumns() {
        // schema.sql 的 NOT NULL 数：直线 10 / 力矩 12 / 音圈 10（R13 漂移防线）
        assertThat(MotorCatalogTemplates.of(MotorType.LINEAR).requiredKeys()).hasSize(10);
        assertThat(MotorCatalogTemplates.of(MotorType.TORQUE).requiredKeys()).hasSize(12);
        assertThat(MotorCatalogTemplates.of(MotorType.COIL).requiredKeys()).hasSize(10);
    }

    @Test
    void fieldCountsMatchDatabaseColumns() {
        // 规格表列数减去 id/motor_id/created_at/updated_at
        assertThat(MotorCatalogTemplates.of(MotorType.LINEAR).fields()).hasSize(21);
        assertThat(MotorCatalogTemplates.of(MotorType.TORQUE).fields()).hasSize(25);
        assertThat(MotorCatalogTemplates.of(MotorType.COIL).fields()).hasSize(20);
    }

    @Test
    void keysAreUniqueWithinEachTemplate() {
        for (MotorType type : MotorType.values()) {
            List<String> keys = MotorCatalogTemplates.of(type).fields().stream()
                    .map(MotorField::key).toList();
            assertThat(new HashSet<>(keys))
                    .as("重复字段 key: %s", type)
                    .hasSize(keys.size());
        }
    }

    @Test
    void onlyVoiceCoilMassFieldsNeedFactorConversion() {
        Map<String, BigDecimal> factors = Map.of(
                "moverMass", new BigDecimal("0.001"),
                "coreMass", new BigDecimal("0.001"));

        MotorTypeTemplate coil = MotorCatalogTemplates.of(MotorType.COIL);
        assertThat(coil.field("moverMass").orElseThrow().factor()).isEqualByComparingTo("0.001");
        assertThat(coil.field("coreMass").orElseThrow().factor()).isEqualByComparingTo("0.001");
        factors.forEach((key, factor) ->
                assertThat(coil.field(key).orElseThrow().factor()).isEqualByComparingTo(factor));

        for (MotorType type : List.of(MotorType.LINEAR, MotorType.TORQUE)) {
            MotorCatalogTemplates.of(type).fields().forEach(field ->
                    assertThat(field.factor()).as("%s.%s factor", type, field.key())
                            .isEqualByComparingTo(BigDecimal.ONE));
        }
    }

    @Test
    void torqueRequiresBothSpeedThresholdsWhileOthersDoNot() {
        Set<String> torqueRequired = new HashSet<>(
                MotorCatalogTemplates.of(MotorType.TORQUE).requiredKeys());
        assertThat(torqueRequired).contains("maxSpeedPeak", "maxSpeedCont");

        assertThat(MotorCatalogTemplates.of(MotorType.LINEAR).requiredKeys())
                .doesNotContain("maxSpeedPeak", "maxSpeedCont");
        assertThat(MotorCatalogTemplates.of(MotorType.COIL).requiredKeys())
                .doesNotContain("maxSpeedPeak", "maxSpeedCont");
    }

    @Test
    void boreIsOnlyAvailableForTorque() {
        assertThat(MotorCatalogTemplates.of(MotorType.TORQUE).bores()).containsExactly("标准", "特殊");
        assertThat(MotorCatalogTemplates.of(MotorType.LINEAR).bores()).isEmpty();
        assertThat(MotorCatalogTemplates.of(MotorType.COIL).bores()).isEmpty();
    }

    @Test
    void derivedItemsFollowPrototypeFormulas() {
        Map<String, BigDecimal> spec = Map.of(
                "iCont", new BigDecimal("2.14"),
                "iPeak", new BigDecimal("8.57"),
                "r25", new BigDecimal("8.8"),
                "kf", new BigDecimal("5.6"));

        MotorDerived derived = MotorDerivedCalculator.compute(MotorType.LINEAR, spec);
        assertThat(derived.prated()).isEqualByComparingTo("40.30048");
        assertThat(derived.pmax()).isEqualByComparingTo("646.31512");
        // 三相：kf / √(1.5·r25)
        assertThat(derived.kmDerived().doubleValue()).isCloseTo(1.5413, org.assertj.core.data.Offset.offset(0.001));

        // 音圈为单相：kf / √r25
        MotorDerived coil = MotorDerivedCalculator.compute(MotorType.COIL, spec);
        assertThat(coil.kmDerived().doubleValue()).isCloseTo(1.887, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void derivedIsNullWhenDependenciesMissing() {
        MotorDerived derived = MotorDerivedCalculator.compute(MotorType.LINEAR, Map.of());
        assertThat(derived.prated()).isNull();
        assertThat(derived.pmax()).isNull();
        assertThat(derived.kmDerived()).isNull();
    }
}
