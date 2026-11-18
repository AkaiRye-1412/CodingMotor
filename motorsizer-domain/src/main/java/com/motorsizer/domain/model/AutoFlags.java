package com.motorsizer.domain.model;

/**
 * 自定义模式的自动反算标记：某项为 {@code true} 表示该参数由系统反解。
 *
 * <p>顺序与原型 {@code auto {time,vMax,aMax}} 保持一致。
 */
public record AutoFlags(boolean time, boolean vMax, boolean aMax) {

    public static final AutoFlags NONE = new AutoFlags(false, false, false);
}
