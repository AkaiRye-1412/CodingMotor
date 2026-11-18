package com.motorsizer.domain.catalog;

import java.math.BigDecimal;

/**
 * 参数表的派生项（只读、不入库、不参与任何计算）。
 *
 * <p>任一依赖字段缺失时对应值为 {@code null}，前端据此决定是否回落展示。
 */
public record MotorDerived(BigDecimal prated, BigDecimal pmax, BigDecimal kmDerived) {
}
