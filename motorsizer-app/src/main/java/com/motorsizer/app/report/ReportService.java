package com.motorsizer.app.report;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.common.FieldError;
import com.motorsizer.app.dto.CalculationMotor;
import com.motorsizer.app.dto.CalculationRequest;
import com.motorsizer.app.dto.CalculationResponse;
import com.motorsizer.app.dto.ReportRequest;
import com.motorsizer.app.service.CalculationService;
import com.motorsizer.app.service.MotorService;
import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.units.ValueFormatter;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * S4 报告服务：逐方案复用 S3 计算 → 组装 Thymeleaf 视图 → HTML 转 PDF。
 *
 * <p>报告与屏幕同源：不重新实现任何计算，曲线按同一份 sampling 数据在服务端重绘。
 * 内容口径与界面一致（第 69 轮）：只导出「选入电机」，不再列出全部推荐电机。
 * 第 91 轮按界面模块补齐：每方案加「计算设置」条、工况输入补「方向」行、供电结论改两张卡。
 */
@Service
public class ReportService {

    private static final int MAX_PLANS = 50;

    private final CalculationService calculationService;
    private final MotorService motorService;
    private final SpringTemplateEngine templateEngine;
    private final ReportPdfRenderer pdfRenderer;

    public ReportService(CalculationService calculationService,
                         MotorService motorService,
                         SpringTemplateEngine templateEngine,
                         ReportPdfRenderer pdfRenderer) {
        this.calculationService = calculationService;
        this.motorService = motorService;
        this.templateEngine = templateEngine;
        this.pdfRenderer = pdfRenderer;
    }

    public ReportPdf generate(ReportRequest request) {
        validateHeader(request);
        ReportModel model = buildModel(request);
        Context context = new Context();
        context.setVariable("report", model);
        String html = templateEngine.process("report", context);
        String filename = suggestedFilename(request);
        return new ReportPdf(pdfRenderer.render(html), filename);
    }

    /**
     * 「另存为」对话框的预填文件名。
     *
     * <p>它在用户选路径之前调用（先选路径再生成 PDF），此时请求还没过校验，
     * 所以这里只做容错取字段，绝不抛校验错误。
     */
    public String suggestedFilename(ReportRequest request) {
        String project = request == null ? null : trimToNull(request.projectName());
        String axis = request == null ? null : trimToNull(request.axisName());
        if (project == null && axis == null) {
            return "电机选型报告.pdf";
        }
        if (axis == null) {
            return sanitizeFilename(project) + "-电机选型报告.pdf";
        }
        if (project == null) {
            return sanitizeFilename(axis) + "-电机选型报告.pdf";
        }
        return filename(project, axis);
    }

    // ---------------------------------------------------------------- 校验

    private void validateHeader(ReportRequest request) {
        if (request == null) {
            throw validationError("body", "请求体不能为空");
        }
        List<FieldError> errors = new ArrayList<>();

        requireText(request.projectName(), "projectName", "项目名称不能为空", 100, errors);
        requireText(request.axisName(), "axisName", "轴名称不能为空", 100, errors);
        requireText(request.reportDate() == null ? null : request.reportDate().toString(),
                "reportDate", "报告日期不能为空", 10, errors);
        if (parseAxis(request.axisKind()) == null) {
            errors.add(new FieldError("axisKind", "页签类型只能为 linear / rotary"));
        }
        optionalText(request.customerName(), "customerName", 100, errors);
        optionalText(request.workingConditionNote(), "workingConditionNote", 500, errors);

        if (request.settings() == null) {
            errors.add(new FieldError("settings", "报告设置不能为空"));
        } else {
            if (request.settings().mode() == null
                    || (!"pointToPoint".equals(request.settings().mode())
                    && !"custom".equals(request.settings().mode()))) {
                errors.add(new FieldError("settings.mode", "运动模式只能为 pointToPoint / custom"));
            }
            if (request.settings().smooth() == null) {
                errors.add(new FieldError("settings", "S 曲线设置不能为空"));
            }
            if (request.settings().margin() == null) {
                errors.add(new FieldError("settings", "安全余量设置不能为空"));
            }
        }

        if (request.plans() == null || request.plans().isEmpty()) {
            throw new BusinessException(ErrorCode.REPORT_NO_PLAN,
                    ErrorCode.REPORT_NO_PLAN.defaultMessage());
        }
        if (request.plans().size() > MAX_PLANS) {
            errors.add(new FieldError("plans", "方案数不能超过 " + MAX_PLANS));
        }

        Set<Integer> planNos = new HashSet<>();
        for (int index = 0; index < request.plans().size(); index++) {
            ReportRequest.PlanItem item = request.plans().get(index);
            if (item == null) {
                errors.add(new FieldError("plans[" + index + "]", "方案不能为空"));
                continue;
            }
            Integer planNo = item.planNo() == null ? index + 1 : item.planNo();
            if (planNo < 1 || planNo > request.plans().size()) {
                errors.add(new FieldError("plans[" + index + "].planNo",
                        "方案序号必须在 1–" + request.plans().size() + " 之间"));
            } else if (!planNos.add(planNo)) {
                errors.add(new FieldError("plans[" + index + "].planNo", "方案序号不能重复"));
            }
            String direction = item.direction();
            if (direction != null && !"up".equals(direction) && !"down".equals(direction)) {
                errors.add(new FieldError("plans[" + index + "].direction", "方向只能为 up / down"));
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败", errors);
        }
    }

    // ---------------------------------------------------------------- 视图模型

    private ReportModel buildModel(ReportRequest request) {
        AxisKind axis = AxisKind.fromCode(request.axisKind());
        ReportRequest.Settings settings = request.settings();
        boolean pointToPoint = "pointToPoint".equals(settings.mode());
        List<ReportModel.ReportPlanModel> plans = new ArrayList<>();
        boolean anyComputable = false;

        for (int index = 0; index < request.plans().size(); index++) {
            ReportRequest.PlanItem item = request.plans().get(index);
            int planNo = item.planNo() == null ? index + 1 : item.planNo();
            List<ReportModel.ReportChip> chips =
                    settingChips(pointToPoint, item.shape(), settings);

            if (item.plan() == null) {
                plans.add(errorPlan(planNo, "方案输入为空", null, pointToPoint, null,
                        item.direction(), chips));
                continue;
            }

            CalculationRequest calculationRequest = new CalculationRequest(
                    request.axisKind(),
                    settings.mode(),
                    item.shape(),
                    settings.smooth(),
                    item.plan(),
                    settings.margin(),
                    new CalculationRequest.Filters(List.of(), List.of()),
                    item.selectedMotorId());

            try {
                CalculationResponse response = calculationService.calculate(calculationRequest);
                if (response.stage() == null || !response.stage().ok()) {
                    plans.add(errorPlan(planNo,
                            response.stage() == null ? "无法计算" : response.stage().message(),
                            axis, pointToPoint, item.plan(), item.direction(), chips));
                    continue;
                }
                plans.add(successPlan(planNo, axis, pointToPoint, item.plan(), item.direction(),
                        response, chips));
                anyComputable = true;
            } catch (BusinessException ex) {
                plans.add(errorPlan(planNo, businessMessage(ex),
                        axis, pointToPoint, item.plan(), item.direction(), chips));
            }
        }

        if (!anyComputable) {
            throw new BusinessException(ErrorCode.REPORT_ALL_UNCOMPUTABLE,
                    ErrorCode.REPORT_ALL_UNCOMPUTABLE.defaultMessage());
        }

        return new ReportModel(
                request.projectName().trim(),
                request.axisName().trim(),
                request.reportDate().toString(),
                axis == AxisKind.LINEAR ? "直线电机" : "旋转电机",
                trimToNull(request.customerName()),
                trimToNull(request.workingConditionNote()),
                plans.size(),
                modeLabel(settings.mode()),
                shapeLabel(firstShape(request.plans())),
                smoothLabel(settings.smooth()),
                marginLabel(settings.margin()),
                plans);
    }

    private ReportModel.ReportPlanModel successPlan(
            int planNo,
            AxisKind axis,
            boolean pointToPoint,
            CalculationRequest.Plan plan,
            String direction,
            CalculationResponse response,
            List<ReportModel.ReportChip> chips) {

        CalculationResponse.Curve curve = response.curve();
        CalculationResponse.Units units = response.units();
        CalculationResponse.Applied applied = response.selectedMotor() == null
                ? null
                : response.selectedMotor().applied();

        List<List<ReportModel.ReportMetric>> metrics = metricRows(response, applied);
        List<ReportModel.ReportPairRow> inputs =
                inputRows(axis, pointToPoint, plan, direction, curve, true);

        ReportModel.SelectedView selectedView = null;
        if (response.selectedMotor() != null) {
            selectedView = selectedView(response.selectedMotor());
        }

        List<ReportModel.ReportRow> appliedRows = appliedRows(axis, applied);
        String verdictText = applied == null ? "" : thermalVerdict(applied);
        String verdictTone = applied == null ? "unknown" : verdictTone(applied);
        String supplyText = applied == null || applied.supply() == null
                ? "未填写供电母线，未做供电判定"
                : applied.supply().text();
        String overVoltage = applied != null && Boolean.TRUE.equals(applied.overBusVoltage())
                ? "需求电压 > 电机 Ubus 耐压"
                : "";
        boolean supplyOk = applied != null && applied.supply() != null
                && Boolean.TRUE.equals(applied.supply().pass());
        // 供电两张卡（与界面 M8 的「供电电压 / 供电余量」同口径），无供电母线时不下发
        String supplyHaveText = applied == null || applied.supply() == null
                ? null
                : fmt(applied.supply().have(), 1);
        String supplyRemainText = applied == null || applied.supply() == null
                ? null
                : fmt(applied.supply().remain(), 1);

        String speedChart = null;
        String forceChart = null;
        if (response.sampling() != null && curve != null) {
            double accelerationEnd = curve.tAccel();
            double cruiseEnd = curve.tAccel() + Math.max(curve.tCruise(), 0);
            speedChart = ReportChartRenderer.speedChart(
                    response.sampling().speed(), accelerationEnd, cruiseEnd, units.velocity());
            forceChart = ReportChartRenderer.forceChart(
                    response.sampling().force(), accelerationEnd, cruiseEnd, units.force());
        }

        String modelLabel = selectedView == null ? "未选取电机" : selectedView.modelLabel();

        return new ReportModel.ReportPlanModel(
                planNo,
                modelLabel,
                true,
                null,
                chips,
                metrics,
                inputs,
                selectedView,
                appliedRows,
                verdictText,
                verdictTone,
                overVoltage,
                supplyText,
                supplyOk,
                supplyHaveText,
                supplyRemainText,
                speedChart,
                forceChart,
                units == null ? "" : units.velocity(),
                units == null ? "" : units.force());
    }

    /**
     * 无法计算的方案：仍保留「工况输入」表（右列全部为「—」），让读者看得到提交了什么，
     * 也避免该页除一个红框外整页空白。
     */
    private static ReportModel.ReportPlanModel errorPlan(
            int planNo,
            String message,
            AxisKind axis,
            boolean pointToPoint,
            CalculationRequest.Plan plan,
            String direction,
            List<ReportModel.ReportChip> chips) {
        return new ReportModel.ReportPlanModel(
                planNo,
                "无法计算",
                false,
                message == null ? "无法计算" : message,
                chips,
                List.of(),
                plan == null || axis == null
                        ? List.of()
                        : inputRows(axis, pointToPoint, plan, direction, null, false),
                null,
                List.of(),
                "",
                "unknown",
                "",
                "",
                false,
                null,
                null,
                null,
                null,
                "",
                "");
    }

    /**
     * 把计算异常转为可读原因：优先拼接字段级原因，避免报告中只显示「参数校验失败」这类泛化文案
     * （04 §3 D1 要求「标注该方案无法计算：原因」）。
     */
    private static String businessMessage(BusinessException ex) {
        List<FieldError> errors = ex.errors();
        if (errors.isEmpty()) {
            return ex.getMessage();
        }
        StringBuilder message = new StringBuilder(ex.getMessage());
        message.append("：");
        for (int index = 0; index < errors.size(); index++) {
            if (index > 0) {
                message.append("；");
            }
            message.append(errors.get(index).reason());
        }
        return message.toString();
    }

    // ---------------------------------------------------------------- 行组装

    /** 理论计算结果 6 张指标卡（顺序与 M6 一致），按 3 列一行分组供模板直接渲染。 */
    private List<List<ReportModel.ReportMetric>> metricRows(
            CalculationResponse response, CalculationResponse.Applied applied) {

        CalculationResponse.Theoretical theoretical = response.theoretical();
        CalculationResponse.Curve curve = response.curve();
        CalculationResponse.Units units = response.units();
        if (theoretical == null || curve == null || units == null) {
            return List.of();
        }
        List<ReportModel.ReportMetric> metrics = new ArrayList<>();
        metrics.add(metric("持续力 / 力矩", fmt(theoretical.contForce(), 1), units.force(), false));
        metrics.add(metric("峰值力 / 力矩", fmt(theoretical.peakForce(), 1), units.force(), false));
        metrics.add(metric("最大加速度", fmt(curve.peakAcceleration(), 1), units.acceleration(), false));
        metrics.add(metric("最大速度", fmt(curve.peakVelocity(), 1), units.velocity(), false));
        // 安全余量与推荐列表 Safety 同口径（含下限门槛）：为负 = 连下限都没达到，必须一眼看出
        Double safety = applied == null ? null : applied.safetyPercent();
        metrics.add(new ReportModel.ReportMetric(
                "安全余量",
                safety == null ? "—" : fmt(safety, 1),
                "%",
                true,
                safety != null && safety < 0));
        metrics.add(metric("总循环周期", fmt(theoretical.cycleTime(), 1), "s", false));
        List<List<ReportModel.ReportMetric>> rows = new ArrayList<>();
        for (int index = 0; index < metrics.size(); index += 3) {
            rows.add(metrics.subList(index, Math.min(index + 3, metrics.size())));
        }
        return rows;
    }

    /**
     * 工况输入：左列用户输入、右列实际参与计算的值（与 M4 双列表一一对应）。
     * 点到点模式下最大速度 / 最大加速度由系统反解，用户输入列显示「—」。
     *
     * <p>CAL-26：总移动质量 / 负载惯量的右列是「用户负载 + 电机自身质量/惯量」，
     * 因此紧跟一行单独列出电机那一项，与界面双列完全对应。
     */
    private static List<ReportModel.ReportPairRow> inputRows(
            AxisKind axis,
            boolean pointToPoint,
            CalculationRequest.Plan plan,
            String direction,
            CalculationResponse.Curve curve,
            boolean computed) {
        boolean linear = axis == AxisKind.LINEAR;
        Double load = plan == null ? null : plan.load();
        Double motorMass = plan == null || plan.motorMass() == null ? null : plan.motorMass();
        Double totalLoad = load == null ? null : load + (motorMass == null ? 0 : motorMass);
        List<ReportModel.ReportPairRow> rows = new ArrayList<>();
        rows.add(pair(linear ? "总移动质量" : "负载惯量",
                fmt(load, 4),
                computed ? fmt(totalLoad, 4) : "—",
                linear ? "kg" : "kg·m²"));
        rows.add(pair(linear ? "线圈质量" : "电机惯量",
                fmt(motorMass, 4),
                computed ? fmt(motorMass, 4) : "—",
                linear ? "kg" : "kg·m²"));
        rows.add(pair(linear ? "运动距离" : "旋转角度",
                fmt(plan == null ? null : plan.distance(), 4),
                computed ? fmt(plan == null ? null : plan.distance(), 4) : "—",
                linear ? "m" : "°"));
        rows.add(pair("运动时间",
                plan == null ? null : plan.time() == null ? "自动" : fmt(plan.time(), 4),
                computed ? fmt(curve == null ? null : curve.time(), 3) : "—",
                "s"));
        rows.add(pair("最大加速度",
                inputOrAuto(plan == null ? null : plan.maxAcceleration(), pointToPoint),
                computed ? fmt(curve == null ? null : curve.peakAcceleration(), 3) : "—",
                linear ? "m/s²" : "rpm/s"));
        rows.add(pair("停顿时间",
                fmt(plan == null ? null : plan.dwell(), 4),
                computed ? fmt(plan == null ? null : plan.dwell(), 4) : "—",
                "s"));
        rows.add(pair("最大速度",
                inputOrAuto(plan == null ? null : plan.maxVelocity(), pointToPoint),
                computed ? fmt(curve == null ? null : curve.peakVelocity(), 3) : "—",
                linear ? "m/s" : "rpm"));
        rows.add(pair(linear ? "阻力" : "摩擦力矩",
                fmt(plan == null ? null : plan.resistance(), 4),
                computed ? fmt(plan == null ? null : plan.resistance(), 4) : "—",
                linear ? "N" : "N·m"));
        // 界面 M4 的「方向（上/下）」单选：只是倾斜角的快捷设定，计算列没有对应的值
        if (linear) {
            rows.add(pair("方向", directionLabel(direction, plan), "—", ""));
        }
        rows.add(pair("倾斜角度",
                fmt(plan == null ? null : plan.tiltDeg(), 2),
                computed ? fmt(plan == null ? null : plan.tiltDeg(), 2) : "—",
                "°"));
        rows.add(pair("环境温度",
                fmt(plan == null ? null : plan.ambientTemp(), 2),
                computed ? fmt(plan == null ? null : plan.ambientTemp(), 2) : "—",
                "°C"));
        rows.add(pair("供电母线",
                fmt(plan == null ? null : plan.supplyVoltage(), 2),
                computed ? fmt(plan == null ? null : plan.supplyVoltage(), 2) : "—",
                "VDC"));
        return rows;
    }

    private ReportModel.SelectedView selectedView(CalculationResponse.SelectedMotor selected) {
        String label;
        try {
            CalculationMotor motor = motorService.loadCalculationMotor(selected.motorId());
            label = motorLabel(motor.modelName(), motor.configName());
        } catch (BusinessException ex) {
            label = "电机 #" + selected.motorId();
        }
        List<ReportModel.ReportRow> parameters = new ArrayList<>();
        for (CalculationResponse.MotorParameter parameter : selected.parameters()) {
            // 2026-11-20：参数表只剩「要求识别（采集）的参数」，派生功率已不进列表，
            // 因此统一 4 位小数即可，不再为 prated/pmax 单独降精度。
            parameters.add(row(parameter.label(), fmt(parameter.value(), 4), parameter.unit()));
        }
        boolean recommended = selected.inRecommendation();
        return new ReportModel.SelectedView(
                label,
                recommended ? "在推荐范围内" : "不在推荐范围",
                recommended,
                trimToNull(selected.outOfRangeReason()),
                parameters);
    }

    private List<ReportModel.ReportRow> appliedRows(
            AxisKind axis, CalculationResponse.Applied applied) {
        if (applied == null) {
            return List.of();
        }
        boolean linear = axis == AxisKind.LINEAR;
        String currentConstant = linear ? "力常数 Kf" : "转矩常数 Kt";
        List<ReportModel.ReportRow> rows = new ArrayList<>();
        String thermalMissing = applied.thermalMissing() == null || applied.thermalMissing().isEmpty()
                ? null
                : "缺 " + String.join(" / ", applied.thermalMissing());
        rows.add(row("线圈温度", appliedValue(applied.coilTemp(), thermalMissing), "°C"));
        rows.add(row("连续电流", appliedValue(applied.contCurrent(), "缺 " + currentConstant), "A"));
        rows.add(row("峰值电流", appliedValue(applied.peakCurrent(), "缺 " + currentConstant), "A"));
        rows.add(row("直流母线电压", appliedValue(applied.busVoltage(),
                applied.missing().isEmpty() ? null : "缺 " + String.join(" / ", applied.missing())), "Vdc"));
        rows.add(row("安全余量", fmt(applied.safetyPercent(), 1), "%"));
        rows.add(row("持续" + (linear ? "力" : "力矩") + "负载系数",
                fmt(applied.contLoadPercent(), 1), "%"));
        rows.add(row("峰值" + (linear ? "力" : "力矩") + "负载系数",
                fmt(applied.peakLoadPercent(), 1), "%"));
        return rows;
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 每方案页首的「计算设置」条：对应界面左列「运动模式」+ 中列「安全余量」两块。
     * 形状是方案级字段，所以这一条放在方案页而不是封面。
     */
    private static List<ReportModel.ReportChip> settingChips(
            boolean pointToPoint, String shape, ReportRequest.Settings settings) {
        List<ReportModel.ReportChip> chips = new ArrayList<>();
        chips.add(new ReportModel.ReportChip("运动模式", modeLabel(settings.mode())));
        chips.add(new ReportModel.ReportChip("运动形状", pointToPoint ? shapeLabel(shape) : "不适用"));
        chips.add(new ReportModel.ReportChip("S 曲线", smoothLabel(settings.smooth())));
        chips.add(new ReportModel.ReportChip("安全余量门槛", marginLabel(settings.margin())));
        return chips;
    }

    /**
     * 「方向」行：优先用前端提交的单选值（界面显示什么报告就显示什么）；
     * 老请求没带该字段时按倾斜角的正弦符号反推（上 = 90°、下 = 270°）。
     */
    private static String directionLabel(String direction, CalculationRequest.Plan plan) {
        if ("up".equals(direction)) {
            return "上";
        }
        if ("down".equals(direction)) {
            return "下";
        }
        double tilt = plan == null || plan.tiltDeg() == null ? 0 : plan.tiltDeg();
        double sin = Math.sin(Math.toRadians(tilt));
        if (sin > 1e-9) {
            return "上";
        }
        return sin < -1e-9 ? "下" : "—";
    }

    private static ReportModel.ReportMetric metric(
            String label, String value, String unit, boolean emphasis) {
        return new ReportModel.ReportMetric(label, value, unit, emphasis, false);
    }

    private static ReportModel.ReportPairRow pair(
            String label, String input, String computed, String unit) {
        return new ReportModel.ReportPairRow(label, input, computed, unit);
    }

    private static ReportModel.ReportRow row(String label, String value, String unit) {
        return new ReportModel.ReportRow(label, value == null || "—".equals(value) ? "—" : value, unit);
    }

    /** 输入列：点到点模式下该列不可填（系统反解），显示「—」；自定义模式留空显示「自动」。 */
    private static String inputOrAuto(Double value, boolean pointToPoint) {
        if (pointToPoint) {
            return "—";
        }
        return value == null ? "自动" : fmt(value, 4);
    }

    private static String appliedValue(Double value, String missing) {
        if (value != null) {
            return fmt(value, 1);
        }
        return missing == null ? "—" : "—（" + missing + "）";
    }

    private static String fmt(Double value, int digits) {
        return ValueFormatter.format(value, digits);
    }

    private static String motorLabel(String modelName, String configName) {
        return configName == null || "默认".equals(configName)
                ? modelName
                : modelName + " / " + configName;
    }

    private static String modeLabel(String mode) {
        return "pointToPoint".equals(mode) ? "点到点" : "自定义";
    }

    private static String shapeLabel(String shape) {
        if (shape == null) {
            return "—";
        }
        return "triangle".equals(shape) ? "三角形" : "梯形";
    }

    /** 形状是方案级字段，抬头只展示首条方案的值（前端一次导出内保持一致）。 */
    private static String firstShape(List<ReportRequest.PlanItem> plans) {
        for (ReportRequest.PlanItem item : plans) {
            if (item != null && item.shape() != null) {
                return item.shape();
            }
        }
        return null;
    }

    private static String smoothLabel(CalculationRequest.Smooth smooth) {
        if (smooth == null || !Boolean.TRUE.equals(smooth.enabled())) {
            return "关闭";
        }
        double tau = smooth.tauMs() == null ? 16 : smooth.tauMs();
        return "开启（τ = " + ValueFormatter.format(tau, 1) + " ms）";
    }

    private static String marginLabel(CalculationRequest.Margin margin) {
        if (margin == null) {
            return "—";
        }
        double lower = margin.lowerPercent() == null ? 30 : margin.lowerPercent();
        double upper = margin.upperPercent() == null ? 300 : margin.upperPercent();
        if (Boolean.TRUE.equals(margin.upperEnabled())) {
            return ValueFormatter.format(lower, 0) + "% – " + ValueFormatter.format(upper, 0) + "%";
        }
        return "≥ " + ValueFormatter.format(lower, 0) + "%";
    }

    private static String thermalVerdict(CalculationResponse.Applied applied) {
        String verdict = applied.thermalVerdict();
        if ("pass".equals(verdict)) {
            return "电机选型合适";
        }
        if ("fail".equals(verdict)) {
            return "线圈温度超限";
        }
        // 说清到底缺什么：样册入库的电机通常缺「最高线圈温度 Tmax」（采集口径里不含这一行）
        List<String> thermalMissing = applied.thermalMissing();
        return thermalMissing == null || thermalMissing.isEmpty()
                ? "热校核数据不足"
                : "热校核数据不足（缺 " + String.join(" / ", thermalMissing) + "）";
    }

    private static String verdictTone(CalculationResponse.Applied applied) {
        String verdict = applied.thermalVerdict();
        if ("pass".equals(verdict)) {
            return "pass";
        }
        return "fail".equals(verdict) ? "fail" : "unknown";
    }

    private static String filename(String projectName, String axisName) {
        return sanitizeFilename(projectName) + "-" + sanitizeFilename(axisName) + "-电机选型报告.pdf";
    }

    /** Windows 文件名禁用字符替换：另存为对话框里不能出现 \ / : * ? " < > |。 */
    private static String sanitizeFilename(String value) {
        return value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void requireText(
            String value, String field, String message, int maxLength, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldError(field, message));
            return;
        }
        if (value.length() > maxLength) {
            errors.add(new FieldError(field, "长度不能超过 " + maxLength));
        }
    }

    private static void optionalText(
            String value, String field, int maxLength, List<FieldError> errors) {
        if (value != null && value.length() > maxLength) {
            errors.add(new FieldError(field, "长度不能超过 " + maxLength));
        }
    }

    private static AxisKind parseAxis(String code) {
        try {
            return AxisKind.fromCode(code);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static BusinessException validationError(String field, String reason) {
        return new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                List.of(new FieldError(field, reason)));
    }

    public record ReportPdf(byte[] content, String filename) {
    }
}
