package com.motorsizer.app.service;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 录入必填（2026-11-20 用户裁定）：直线/音圈必须有线圈质量、力矩必须有转动惯量。
 *
 * <p>它们<b>不是</b>模板 {@code required}，因为 {@code required=true} 会连带把选型准入
 * 判成 UNKNOWN（历史电机都没有这两项）。所以校验落在录入这一层，且编辑老电机时
 * 「原本就缺」的放过——库里 2026-11-20 之前录的电机基本都没有这两个值。
 */
class MotorSpecValidatorTest {

    private final MotorSpecValidator validator = new MotorSpecValidator();

    @Test
    void torqueCreateWithInertiaPasses() {
        MotorSpecValidator.ValidatedMotor validated =
                validator.validate(request(MotorType.TORQUE, torqueSpec(true)), null);

        assertThat(validated.type()).isEqualTo(MotorType.TORQUE);
        assertThat(validated.spec()).containsEntry("inertia", new BigDecimal("0.015"));
    }

    @Test
    void torqueCreateWithoutInertiaIsRejected() {
        assertThatThrownBy(() -> validator.validate(request(MotorType.TORQUE, torqueSpec(false)), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必填规格字段缺失");
    }

    @Test
    void linearCreateWithoutMoverMassIsRejected() {
        assertThatThrownBy(() -> validator.validate(request(MotorType.LINEAR, linearSpec(false)), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必填规格字段缺失");
    }

    @Test
    void coilCreateWithoutMoverMassIsRejected() {
        assertThatThrownBy(() -> validator.validate(request(MotorType.COIL, coilSpec(false)), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必填规格字段缺失");
    }

    /** 编辑老电机：库中原本就没有惯量、本次也没填 → 放过（不逼用户补历史数据）。 */
    @Test
    void editingLegacyMotorWithoutInertiaIsAllowed() {
        assertThatCode(() -> validator.validate(
                request(MotorType.TORQUE, torqueSpec(false)), MotorType.TORQUE, Map.of()))
                .doesNotThrowAnyException();
    }

    /** 编辑时主动清空原本有值的惯量 → 报错（这算「动了」，不是「不动」）。 */
    @Test
    void clearingExistingInertiaIsRejected() {
        Map<String, BigDecimal> existing = Map.of("inertia", new BigDecimal("0.015"));

        assertThatThrownBy(() -> validator.validate(
                request(MotorType.TORQUE, torqueSpec(false)), MotorType.TORQUE, existing))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必填规格字段缺失");
    }

    // ---------------------------------------------------------------- 夹具

    private static MotorUpsertRequest request(MotorType type, Map<String, Object> spec) {
        MotorTypeTemplate template = MotorCatalogTemplates.of(type);
        return new MotorUpsertRequest("TEST-1", "默认", type.code(),
                template.structures().get(0), template.coolings().get(0),
                "", "测试厂商", "", "", "",
                "手工录入", null, "", spec);
    }

    private static Map<String, Object> torqueSpec(boolean withInertia) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("torqueCont", 3.4);
        spec.put("torquePeak", 10.0);
        spec.put("kt", 3.0);
        spec.put("ke", 62);
        spec.put("r25", 1.92);
        spec.put("iCont", 3.8);
        spec.put("iPeak", 12.2);
        spec.put("kth", 0.715);
        spec.put("uBus", 600);
        if (withInertia) {
            spec.put("inertia", 0.015);
        }
        return spec;
    }

    private static Map<String, Object> linearSpec(boolean withMass) {
        Map<String, Object> spec = forceSpec();
        if (withMass) {
            spec.put("moverMass", 1.1);
        }
        return spec;
    }

    private static Map<String, Object> coilSpec(boolean withMass) {
        Map<String, Object> spec = forceSpec();
        spec.put("stroke", 8);
        if (withMass) {
            spec.put("moverMass", 0.045);
        }
        return spec;
    }

    private static Map<String, Object> forceSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("forceCont", 120);
        spec.put("forcePeak", 360);
        spec.put("kf", 40);
        spec.put("ke", 33);
        spec.put("r25", 2.4);
        spec.put("iCont", 3.0);
        spec.put("iPeak", 9.0);
        spec.put("kth", 1.2);
        spec.put("uBus", 600);
        return spec;
    }
}
