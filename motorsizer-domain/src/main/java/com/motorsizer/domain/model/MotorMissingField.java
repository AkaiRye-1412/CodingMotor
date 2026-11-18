package com.motorsizer.domain.model;

/** 参与判定所缺的必填字段（key 为 K2 字段名，label 为界面文案）。 */
public record MotorMissingField(String key, String label) {
}
