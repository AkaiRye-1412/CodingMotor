package com.motorsizer.domain.sampling;

import java.util.List;

/**
 * 曲线采样结果：均匀 241 点（240 个等分区间，含 t=0 与 t=T）。
 *
 * <p>v1 不区分 S 曲线加密（2026-11-18 复核确认）；屏幕与报告共享同一份数据。
 */
public record SamplingResult(List<SamplePoint> points) {

    public SamplingResult {
        points = List.copyOf(points);
    }

    public int pointCount() {
        return points.size();
    }
}
