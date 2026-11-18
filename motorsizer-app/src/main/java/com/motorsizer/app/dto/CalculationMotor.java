package com.motorsizer.app.dto;

import com.motorsizer.domain.catalog.MotorDerived;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.model.MotorParameters;

import java.math.BigDecimal;
import java.util.Map;

/** 计算用电机快照：公共标识 + 数值 spec + 派生项 + Domain 参数集合。 */
public record CalculationMotor(
        Long motorId,
        String modelName,
        String configName,
        MotorType motorType,
        String structure,
        String cooling,
        Map<String, BigDecimal> numericSpec,
        MotorDerived derived,
        MotorParameters parameters) {

    public CalculationMotor {
        numericSpec = Map.copyOf(numericSpec);
    }
}
