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
        // schema.sql 的 NOT NULL 数：直线 9 / 力矩 9 / 音圈 9（R13 漂移防线）
        // 力矩第 66 轮由 12 降为 10（双转速阈值改选填）；
        // 第 67 轮三套模板再由 10 降为 9（最高线圈温度改选填，缺值不做热校核）
        assertThat(MotorCatalogTemplates.of(MotorType.LINEAR).requiredKeys()).hasSize(9);
        assertThat(MotorCatalogTemplates.of(MotorType.TORQUE).requiredKeys()).hasSize(9);
        assertThat(MotorCatalogTemplates.of(MotorType.COIL).requiredKeys()).hasSize(9);
    }

    @Test
    void fieldCountsMatchDatabaseColumns() {
        // 规格表列数减去 id/motor_id/created_at/updated_at
        assertThat(MotorCatalogTemplates.of(MotorType.LINEAR).fields()).hasSize(21);
        // 第 68 轮：力矩模板加「线圈质量」(moverMass)，25 → 26（schema 同步加 mover_mass 列）
        assertThat(MotorCatalogTemplates.of(MotorType.TORQUE).fields()).hasSize(26);
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

    /**
     * 2026-11-20（CAL-25）：三套模板的 {@code unit} 一律等于内部单位 —— 音圈质量也是 kg、
     * {@code factor} 恒为 1。样册给 g 时的换算只发生在入库层（{@code CatalogRowMapper.scaleOf}），
     * 模板不再承担「录入单位 ≠ 内部单位」。
     *
     * <p>用户原话：AVM20-HF-35 的参数表显示「线圈质量 0.0729 g」——值已经按 g→kg 换算了，
     * 单位却还是 g，**半截换算**比不换算更误导。现在统一按 kg。
     */
    @Test
    void everyTemplateUnitIsAlsoInternalUnit() {
        for (MotorType type : MotorType.values()) {
            MotorCatalogTemplates.of(type).fields().forEach(field ->
                    assertThat(field.factor()).as("%s.%s factor 必须为 1", type, field.key())
                            .isEqualByComparingTo(BigDecimal.ONE));
        }

        MotorTypeTemplate coil = MotorCatalogTemplates.of(MotorType.COIL);
        assertThat(coil.field("moverMass").orElseThrow().unit()).isEqualTo("kg");
        assertThat(coil.field("coreMass").orElseThrow().unit()).isEqualTo("kg");
    }

    /** 第 66 轮：三套模板的「最高速度 / 最高转速」一律选填，缺项只跳过准入②。 */
    @Test
    void noTemplateRequiresSpeedThresholds() {
        for (MotorType type : MotorType.values()) {
            assertThat(MotorCatalogTemplates.of(type).requiredKeys())
                    .as("%s 的最高速度阈值不应是必填", type)
                    .doesNotContain("maxSpeedPeak", "maxSpeedCont");
        }
    }

    /**
     * 2026-11-21（用户裁定）：三套模板的 {@code uBus} 必须出现在 M8「选入电机数据」参数表。
     *
     * <p>直线/力矩样册写「最高母线电压」、音圈写「最高电压」，是同一个耐压字段（{@code overUbus}
     * 校核的输入）。只让音圈显示会让直线电机核对参数时看不到供电上限——展示口径必须一致。
     */
    @Test
    void everyTemplateShowsBusVoltageInDetailTable() {
        for (MotorType type : MotorType.values()) {
            MotorField field = MotorCatalogTemplates.of(type).field("uBus").orElseThrow();
            assertThat(field.showsInDetail())
                    .as("%s 的最高母线电压必须出现在 M8 参数表", type)
                    .isTrue();
            assertThat(field.required())
                    .as("%s 的最高母线电压仍是必填（耐压校核输入）", type)
                    .isTrue();
            assertThat(field.showsInForm()).as("%s 的录入表单仍要有该字段", type).isTrue();
        }
    }

    /**
     * 2026-11-21（用户裁定）：音圈 M8 参数表不再显示「铁芯质量」「运动间隙」。
     *
     * <p>口径靠模板 {@code display} 表达（{@code detail} = M8 参数表 / {@code form} = 录入表单），
     * 不在展示层写字段黑名单。两个被隐藏的字段**仍留在录入表单与识别别名里**，
     * 所以这里同时断言 {@code showsInForm()} 为真，避免后人误删字段导致样册识别丢项。
     */
    @Test
    void coilDetailTableHidesCoreMassAndGap() {
        MotorTypeTemplate coil = MotorCatalogTemplates.of(MotorType.COIL);

        for (String key : List.of("coreMass", "gap")) {
            MotorField field = coil.field(key).orElseThrow();
            assertThat(field.showsInDetail()).as("%s 不应出现在 M8 参数表", key).isFalse();
            assertThat(field.showsInForm()).as("%s 仍应在录入表单中", key).isTrue();
        }
    }

    /** 2026-11-21（用户裁定）：直线 M8 参数表不显示「线圈长度」（结构尺寸，样册常缺）。 */
    @Test
    void linearDetailTableHidesCoilLength() {
        MotorField field = MotorCatalogTemplates.of(MotorType.LINEAR).field("coilLength").orElseThrow();
        assertThat(field.showsInDetail()).as("线圈长度不应出现在 M8 参数表").isFalse();
        assertThat(field.showsInForm()).as("线圈长度仍应在录入表单中").isTrue();
    }

    /**
     * 2026-11-21（用户裁定）：旋转（力矩）M8 参数表不显示「线圈质量」。
     *
     * <p>旋转电机的电机惯量取「转动惯量」（CAL-26），线圈质量只是样册信息；
     * 字段本身仍是 ACR 弧形电机入库所必需（第 68 轮），所以只能退到 {@code formOnly}，不能删。
     */
    @Test
    void torqueDetailTableHidesMoverMass() {
        MotorField field = MotorCatalogTemplates.of(MotorType.TORQUE).field("moverMass").orElseThrow();
        assertThat(field.showsInDetail()).as("线圈质量不应出现在 M8 参数表").isFalse();
        assertThat(field.showsInForm()).as("线圈质量仍应在录入表单中").isTrue();
        assertThat(field.aliases()).as("样册识别别名不能丢").isNotEmpty();
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
