package com.motorsizer.domain.model;

import com.motorsizer.domain.catalog.MotorType;

import java.util.HashMap;
import java.util.Map;

/**
 * 参与计算的电机参数集合。
 *
 * <p>key 使用 K2/API 的驼峰字段名（如 {@code forceCont}、{@code kf}、{@code r25}、{@code tMax}），
 * 使「必填判定」可以直接遍历 K2 模板，不需要在 Domain 里硬编码字段清单。
 *
 * <p>缺失或非有限的字段不进入 map；{@link #value(String)} 对缺失字段返回 {@code null}。
 */
public record MotorParameters(MotorType type, Map<String, Double> values) {

    public MotorParameters {
        if (type == null) {
            throw new IllegalArgumentException("电机类型不能为空");
        }
        values = Map.copyOf(values);
    }

    /** 过滤 null / NaN / Infinity，避免 Map.copyOf 抛 NPE，也避免把无效值当成已录入。 */
    public static MotorParameters of(MotorType type, Map<String, Double> rawValues) {
        Map<String, Double> cleaned = new HashMap<>();
        rawValues.forEach((key, value) -> {
            if (key != null && value != null && Double.isFinite(value)) {
                cleaned.put(key, value);
            }
        });
        return new MotorParameters(type, cleaned);
    }

    public Double value(String key) {
        return values.get(key);
    }

    public boolean has(String key) {
        Double value = values.get(key);
        return value != null && Double.isFinite(value);
    }
}
