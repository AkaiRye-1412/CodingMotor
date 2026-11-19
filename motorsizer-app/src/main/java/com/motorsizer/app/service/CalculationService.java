package com.motorsizer.app.service;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.common.FieldError;
import com.motorsizer.app.dto.CalculationMotor;
import com.motorsizer.app.dto.CalculationRequest;
import com.motorsizer.app.dto.CalculationResponse;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import com.motorsizer.domain.model.AppliedEnvironment;
import com.motorsizer.domain.model.AppliedResult;
import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.CustomPlanInput;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.MotionMode;
import com.motorsizer.domain.model.MotionResult;
import com.motorsizer.domain.model.MotorEvaluation;
import com.motorsizer.domain.model.MotorMissingField;
import com.motorsizer.domain.model.PointToPointInput;
import com.motorsizer.domain.model.SupplyVerdict;
import com.motorsizer.domain.model.TheoreticalResult;
import com.motorsizer.domain.model.Verdict;
import com.motorsizer.domain.motion.MotionPlanner;
import com.motorsizer.domain.physics.AppliedCalculator;
import com.motorsizer.domain.physics.ForceCalculator;
import com.motorsizer.domain.sampling.SamplePoint;
import com.motorsizer.domain.sampling.SamplingCalculator;
import com.motorsizer.domain.sampling.SamplingResult;
import com.motorsizer.domain.screening.MotorEvaluator;
import com.motorsizer.domain.units.UnitConversions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S3 计算服务：编排 K1（规划 → 理论 → 候选准入 → 应用值 → 采样）。
 *
 * <p>本类不实现任何公式，只做用例编排与 DTO 组装；计算失败按 04 §1.1 返回 stage.ok=false。
 */
@Service
public class CalculationService {

    private final MotorService motorService;

    public CalculationService(MotorService motorService) {
        this.motorService = motorService;
    }

    public CalculationResponse calculate(CalculationRequest request) {
        CalculationInput input = CalculationRequestValidator.validate(request);

        MotionResult motion = plan(input);
        if (motion instanceof MotionResult.Failure failure) {
            return failureResponse(failure);
        }
        MotionCurve curve = ((MotionResult.Success) motion).curve();

        CalculationMotor selectedMotor = resolveSelected(input);
        TheoreticalResult theoretical = ForceCalculator.theoretical(
                input.axis(),
                input.load(),
                curve,
                input.resistance(),
                input.dwell(),
                input.tiltDeg());

        List<CalculationMotor> pool = motorService.loadCalculationPool(
                input.axis(), input.structures(), input.coolings());

        List<CalculationResponse.Candidate> candidates = evaluateCandidates(input, pool, theoretical, curve);
        CalculationResponse.SelectedMotor selected = selectedMotor == null
                ? null
                : buildSelectedMotor(input, selectedMotor, theoretical, curve, candidates);

        SamplingResult sampling = SamplingCalculator.sample(curve, forceFunction(input));

        return new CalculationResponse(
                CalculationResponse.Stage.success(),
                units(input.axis()),
                curveDto(input.axis(), curve),
                new CalculationResponse.Theoretical(
                        theoretical.cont(),
                        theoretical.peak(),
                        theoretical.cycleTime(),
                        theoretical.gravity()),
                candidates,
                selected,
                samplingDto(input.axis(), sampling));
    }

    // ---------------------------------------------------------------- 规划

    private MotionResult plan(CalculationInput input) {
        // 请求体的速度/加速度使用展示单位（m/s 或 rpm）；进入 K1 前按页签换算为内部单位。
        MotionResult result = input.mode() == MotionMode.POINT_TO_POINT
                ? MotionPlanner.planPointToPoint(new PointToPointInput(
                        input.distance(), input.time(), input.shape()))
                : MotionPlanner.planCustom(new CustomPlanInput(
                        input.distance(),
                        input.time(),
                        input.maxVelocity() == null
                                ? null
                                : UnitConversions.speedToInternal(input.axis(), input.maxVelocity()),
                        input.maxAcceleration() == null
                                ? null
                                : UnitConversions.accelerationToInternal(
                                        input.axis(), input.maxAcceleration())));

        if (result instanceof MotionResult.Success success && input.smoothEnabled()) {
            return MotionPlanner.applySmoothing(success.curve(), input.tauMs());
        }
        return result;
    }

    private CalculationResponse failureResponse(MotionResult.Failure failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (failure.maxTau() != null) {
            details.put("maxTau", failure.maxTau());
        }
        if (failure.closestPreset() != null) {
            details.put("closestPreset", failure.closestPreset());
        }
        return new CalculationResponse(
                CalculationResponse.Stage.failure(
                        failure.code().code(), failure.message(), details.isEmpty() ? null : details),
                null, null, null, null, null, null);
    }

    // ---------------------------------------------------------------- 选中电机

    private CalculationMotor resolveSelected(CalculationInput input) {
        if (input.selectedMotorId() == null) {
            return null;
        }
        CalculationMotor motor = motorService.loadCalculationMotor(input.selectedMotorId());
        if (motor.motorType().axis() != input.axis()) {
            throw new BusinessException(
                    ErrorCode.COMMON_VALIDATION_FAILED,
                    "参数校验失败",
                    List.of(new FieldError("selectedMotorId", "所选电机与当前页签量纲不一致")));
        }
        return motor;
    }

    // ---------------------------------------------------------------- 候选

    private List<CalculationResponse.Candidate> evaluateCandidates(
            CalculationInput input,
            List<CalculationMotor> pool,
            TheoreticalResult theoretical,
            MotionCurve curve) {

        List<EvaluatedMotor> passing = new ArrayList<>();
        for (CalculationMotor motor : pool) {
            MotorEvaluation evaluation = MotorEvaluator.evaluate(
                    input.axis(), motor.parameters(), theoretical, curve, input.marginLow());
            if (evaluation.pass()) {
                passing.add(new EvaluatedMotor(motor, evaluation));
            }
        }

        passing.sort(Comparator.comparingDouble(item -> item.evaluation().headroom()));

        return passing.stream()
                .map(item -> new CalculationResponse.Candidate(
                        item.motor().motorId(),
                        item.motor().modelName(),
                        item.motor().configName(),
                        item.motor().motorType().code(),
                        item.motor().structure(),
                        item.motor().cooling(),
                        item.evaluation().headroom(),
                        input.marginUpperEnabled()
                                && item.evaluation().headroom() * 100 > input.marginUpper(),
                        item.evaluation().reasons(),
                        item.evaluation().speedSkipped()))
                .toList();
    }

    private CalculationResponse.SelectedMotor buildSelectedMotor(
            CalculationInput input,
            CalculationMotor motor,
            TheoreticalResult theoretical,
            MotionCurve curve,
            List<CalculationResponse.Candidate> candidates) {

        boolean inRecommendation = candidates.stream()
                .anyMatch(candidate -> candidate.motorId().equals(motor.motorId()));

        String outOfRangeReason = null;
        if (!inRecommendation) {
            outOfRangeReason = outOfRangeReason(input, motor, theoretical, curve);
        }

        AppliedResult applied = AppliedCalculator.applied(
                input.axis(),
                motor.parameters(),
                theoretical,
                curve,
                new AppliedEnvironment(
                        input.load(),
                        input.resistance(),
                        input.dwell(),
                        input.ambientTemp(),
                        input.supplyVoltage(),
                        input.tiltDeg()));

        return new CalculationResponse.SelectedMotor(
                motor.motorId(),
                inRecommendation,
                outOfRangeReason,
                parameters(motor),
                appliedDto(applied));
    }

    private String outOfRangeReason(
            CalculationInput input,
            CalculationMotor motor,
            TheoreticalResult theoretical,
            MotionCurve curve) {

        boolean excludedByFilter =
                excludedByStructureFilter(input, motor)
                        || (!input.coolings().isEmpty() && !input.coolings().contains(motor.cooling()));
        if (excludedByFilter) {
            return "被当前结构/冷却筛选排除";
        }

        MotorEvaluation evaluation = MotorEvaluator.evaluate(
                input.axis(), motor.parameters(), theoretical, curve, input.marginLow());
        if (evaluation.verdict() == Verdict.UNKNOWN) {
            String labels = evaluation.missing().stream()
                    .map(MotorMissingField::label)
                    .collect(Collectors.joining("、"));
            return "信息不全：" + labels;
        }
        if (evaluation.verdict() == Verdict.FAIL) {
            return "未通过准入：" + String.join("；", evaluation.reasons());
        }
        return null;
    }

    /**
     * 系列筛选是否排除该电机。
     *
     * <p>第 67/68 轮：两个页签的 {@code structures} 都是「系列」前缀，按型号名匹配；
     * 「其他」采用排除法（不以该轴任一已知前缀开头）。
     */
    private boolean excludedByStructureFilter(CalculationInput input, CalculationMotor motor) {
        if (input.structures().isEmpty()) {
            return false;
        }
        List<String> known = MotorSeries.forAxis(input.axis());
        return input.structures().stream()
                .noneMatch(s -> MotorSeries.matches(motor.modelName(), s, known));
    }

    private List<CalculationResponse.MotorParameter> parameters(CalculationMotor motor) {
        MotorTypeTemplate template = MotorCatalogTemplates.of(motor.motorType());
        List<CalculationResponse.MotorParameter> rows = new ArrayList<>();

        for (MotorField field : template.fields()) {
            if (!field.showsInDetail()) {
                continue;
            }
            Double value = null;
            BigDecimal numeric = motor.numericSpec().get(field.key());
            if (numeric != null) {
                value = numeric.doubleValue();
            }

            boolean derived = false;
            if ("km".equals(field.key())
                    && value == null
                    && motor.derived().kmDerived() != null) {
                value = motor.derived().kmDerived().doubleValue();
                derived = true;
            }
            rows.add(new CalculationResponse.MotorParameter(
                    field.key(), field.label(), field.unit(), value, derived));
        }

        rows.add(new CalculationResponse.MotorParameter(
                "prated", "额定功率", "W",
                decimalToDouble(motor.derived().prated()),
                motor.derived().prated() != null));
        rows.add(new CalculationResponse.MotorParameter(
                "pmax", "最大功率", "W",
                decimalToDouble(motor.derived().pmax()),
                motor.derived().pmax() != null));

        return rows;
    }

    // ---------------------------------------------------------------- 响应映射

    private CalculationResponse.Units units(AxisKind axis) {
        if (axis == AxisKind.ROTARY) {
            return new CalculationResponse.Units(
                    "rpm", "rpm/s", "Nm", "°", "s", "°C", "Vdc");
        }
        return new CalculationResponse.Units(
                "m/s", "m/s²", "N", "m", "s", "°C", "Vdc");
    }

    private CalculationResponse.Curve curveDto(AxisKind axis, MotionCurve curve) {
        boolean smooth = curve.smoothApplied();
        return new CalculationResponse.Curve(
                curve.mode().code(),
                curve.shape().code(),
                smooth,
                curve.tau() * 1000,
                curve.time(),
                UnitConversions.speedToDisplay(axis, curve.vPeak()),
                UnitConversions.accelerationToDisplay(axis, curve.aPeak()),
                smooth ? UnitConversions.accelerationToDisplay(axis, curve.aPeakIdeal()) : null,
                smooth && curve.aPeakIdeal() != 0
                        ? (curve.aPeak() / curve.aPeakIdeal() - 1) * 100
                        : null,
                curve.tAccel(),
                curve.tCruise(),
                curve.tDecel(),
                curve.timeDerived(),
                curve.autoShape(),
                curve.note());
    }

    private CalculationResponse.Applied appliedDto(AppliedResult applied) {
        return new CalculationResponse.Applied(
                applied.tCoil(),
                applied.iContRms(),
                applied.iPeakRms(),
                applied.uReq(),
                applied.safety(),
                applied.loadCont(),
                applied.loadPeak(),
                applied.verdict().code(),
                applied.overUbus(),
                supplyDto(applied.supply()),
                applied.uMissing());
    }

    private CalculationResponse.Supply supplyDto(SupplyVerdict supply) {
        return supply == null ? null : new CalculationResponse.Supply(
                supply.have(), supply.need(), supply.remain(), supply.pass(), supply.text());
    }

    private CalculationResponse.Sampling samplingDto(AxisKind axis, SamplingResult sampling) {
        List<CalculationResponse.SpeedPoint> speed = new ArrayList<>(sampling.pointCount());
        List<CalculationResponse.ForcePoint> force = new ArrayList<>(sampling.pointCount());
        for (SamplePoint point : sampling.points()) {
            speed.add(new CalculationResponse.SpeedPoint(
                    point.time(), UnitConversions.speedToDisplay(axis, point.velocity())));
            force.add(new CalculationResponse.ForcePoint(point.time(), point.force()));
        }
        return new CalculationResponse.Sampling(speed, force, sampling.pointCount());
    }

    private com.motorsizer.domain.sampling.ForceFunction forceFunction(CalculationInput input) {
        return (acceleration, velocity) -> input.load()
                * acceleration
                * UnitConversions.accelerationFactor(input.axis())
                + input.resistance()
                + ForceCalculator.gravityTerm(input.axis(), input.load(), input.tiltDeg());
    }

    private static Double decimalToDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private record EvaluatedMotor(CalculationMotor motor, MotorEvaluation evaluation) {
    }
}
