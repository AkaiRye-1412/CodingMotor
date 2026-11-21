package com.motorsizer.app.report;

import java.util.List;

/**
 * Thymeleaf 报告视图模型（只承载展示字符串，不含计算逻辑）。
 *
 * <p>内容口径与屏幕一致（第 69 轮）：每个方案 = 理论计算指标 → 工况输入 → 运动曲线 →
 * 选入电机数据 + 电机实际应用值。
 *
 * <p>第 91 轮按界面模块补齐（用户：「参考现在的这个模式内容」）：
 * ①每方案页首加「计算设置」条，对应界面左列运动模式 + 中列安全余量；
 * ②工况输入补「方向（上/下）」行；
 * ③供电结论由一行文字改成「供电电压 / 供电余量」两张卡，与界面一致。
 *
 * <p>第 98 轮：取消「推荐电机」清单 —— 报告只讲选入的那台（用户：「不需要附上推荐电机清单」）。
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
            /** 计算设置条：运动模式 / 形状 / S 曲线 / 安全余量。 */
            List<ReportChip> settingChips,
            List<List<ReportMetric>> metricRows,
            List<ReportPairRow> inputs,
            SelectedView selected,
            List<ReportRow> applied,
            String verdictText,
            String verdictTone,
            String overVoltageWarning,
            String supplyText,
            boolean supplyOk,
            /** 供电电压（have）/ 供电余量（remain），已按 1 位小数格式化；无供电母线时为 null。 */
            String supplyHaveText,
            String supplyRemainText,
            String speedChart,
            String forceChart,
            String velocityUnit,
            String forceUnit) {
    }

    /** 理论计算结果指标卡（与 M6 的 6 张卡一一对应）。 */
    public record ReportMetric(
            String label, String value, String unit, boolean emphasis, boolean danger) {
    }

    /** 工况输入双列表：左侧用户输入，右侧实际参与计算的值。 */
    public record ReportPairRow(String label, String input, String computed, String unit) {
    }

    public record ReportRow(String label, String value, String unit) {
    }

    /** 计算设置条的一格（小标签 + 值）。 */
    public record ReportChip(String label, String value) {
    }

    public record SelectedView(
            String modelLabel,
            String inRecommendation,
            boolean recommended,
            String outOfRangeReason,
            List<ReportRow> parameters) {
    }
}
