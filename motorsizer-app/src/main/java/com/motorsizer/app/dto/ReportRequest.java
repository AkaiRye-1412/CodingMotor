package com.motorsizer.app.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * D1 报告导出请求（04 §3 D1）。
 *
 * <p>{@code direction} 是报告展示口径，不参与计算：复刻界面上「方向（上/下）」单选
 * （计算只看 {@code tiltDeg}，见 CAL 口径）。
 *
 * <p>报告只导出选入的那台电机，不附「推荐电机」清单（第 98 轮用户裁定）。
 */
public record ReportRequest(
        String projectName,
        String axisName,
        LocalDate reportDate,
        String axisKind,
        String customerName,
        String workingConditionNote,
        Settings settings,
        List<PlanItem> plans) {

    public record Settings(
            String mode,
            CalculationRequest.Smooth smooth,
            CalculationRequest.Margin margin) {
    }

    public record PlanItem(
            Integer planNo,
            CalculationRequest.Plan plan,
            String shape,
            Long selectedMotorId,
            String direction) {
    }
}
