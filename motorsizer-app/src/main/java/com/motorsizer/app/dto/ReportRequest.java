package com.motorsizer.app.dto;

import java.time.LocalDate;
import java.util.List;

/** D1 报告导出请求（04 §3 D1）。 */
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
            Long selectedMotorId) {
    }
}
