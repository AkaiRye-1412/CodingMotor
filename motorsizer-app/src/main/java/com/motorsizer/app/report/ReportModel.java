package com.motorsizer.app.report;

import java.util.List;

/** Thymeleaf 报告视图模型（只承载展示字符串，不含计算逻辑）。 */
public record ReportModel(
        String projectName,
        String axisName,
        String reportDate,
        String customerName,
        String workingConditionNote,
        int planCount,
        String modeLabel,
        String smoothLabel,
        String marginLabel,
        List<ReportPlanModel> plans) {

    public record ReportPlanModel(
            int planNo,
            String summary,
            boolean ok,
            String error,
            List<ReportRow> inputs,
            List<ReportRow> theoretical,
            List<ReportCandidateRow> candidates,
            SelectedView selected,
            List<ReportRow> applied,
            String thermalVerdict,
            String supplyText,
            String overVoltageWarning,
            String speedChart,
            String forceChart,
            String velocityUnit,
            String forceUnit) {
    }

    public record ReportRow(String label, String value, String unit) {
    }

    public record ReportCandidateRow(
            String label,
            String typeLabel,
            String safety,
            String reasons,
            String overSized) {
    }

    public record SelectedView(
            String modelLabel,
            String inRecommendation,
            String outOfRangeReason,
            List<ReportRow> parameters) {
    }
}
