package com.motorsizer.app.report;

import java.util.List;

/**
 * Thymeleaf 报告视图模型（只承载展示字符串，不含计算逻辑）。
 *
 * <p>内容口径与屏幕一致（第 69 轮）：每个方案 = 理论计算指标 → 工况输入 → 运动曲线 →
 * 选入电机数据 + 电机实际应用值。<b>推荐清单不进报告</b>：一屏只关心选中的那台电机，
 * 全量候选会把两方案的报告撑到 30 页以上，且对客户没有价值。
 */
public record ReportModel(
        String projectName,
        String axisName,
        String reportDate,
        String axisLabel,
        String customerName,
        String workingConditionNote,
        int planCount,
        String modeLabel,
        String shapeLabel,
        String smoothLabel,
        String marginLabel,
        List<ReportPlanModel> plans) {

    public record ReportPlanModel(
            int planNo,
            String modelLabel,
            boolean ok,
            String error,
            List<List<ReportMetric>> metricRows,
            List<ReportPairRow> inputs,
            SelectedView selected,
            List<ReportRow> applied,
            String verdictText,
            String verdictTone,
            String overVoltageWarning,
            String supplyText,
            boolean supplyOk,
            String speedChart,
            String forceChart,
            String velocityUnit,
            String forceUnit) {
    }

    /** 理论计算结果指标卡（与 M6 的 6 张卡一一对应）。 */
    public record ReportMetric(String label, String value, String unit, boolean emphasis) {
    }

    /** 工况输入双列表：左侧用户输入，右侧实际参与计算的值。 */
    public record ReportPairRow(String label, String input, String computed, String unit) {
    }

    public record ReportRow(String label, String value, String unit) {
    }

    public record SelectedView(
            String modelLabel,
            String inRecommendation,
            boolean recommended,
            String outOfRangeReason,
            List<ReportRow> parameters) {
    }
}
