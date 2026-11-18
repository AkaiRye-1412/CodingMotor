package com.motorsizer.app.service;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.common.FieldError;
import com.motorsizer.app.dto.CalculationRequest;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.CurveShape;
import com.motorsizer.domain.model.MotionMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * B1 请求校验（04 §1.3 / §1.6）：服务端为权威校验层，所有错误汇成 errors[] 一次返回。
 */
final class CalculationRequestValidator {

    private static final double DEFAULT_TAU_MS = 16;
    private static final double DEFAULT_DWELL = 0;
    private static final double DEFAULT_RESISTANCE = 0;
    private static final double DEFAULT_TILT = 0;
    private static final double DEFAULT_AMBIENT_TEMP = 22;
    private static final double DEFAULT_LOWER_MARGIN = 30;
    private static final double DEFAULT_UPPER_MARGIN = 300;
    private static final int MAX_FILTER_VALUES = 10;

    private CalculationRequestValidator() {
    }

    static CalculationInput validate(CalculationRequest request) {
        if (request == null) {
            throw validationError(List.of(new FieldError("body", "请求体不能为空")));
        }

        List<FieldError> errors = new ArrayList<>();

        AxisKind axis = parseAxis(request.axisKind(), errors);
        MotionMode mode = parseMode(request.mode(), errors);
        CurveShape shape = parseShape(request.shape(), errors);

        boolean smoothEnabled = false;
        double tauMs = DEFAULT_TAU_MS;
        CalculationRequest.Smooth smooth = request.smooth();
        if (smooth == null) {
            errors.add(new FieldError("smooth", "S 曲线设置不能为空"));
        } else {
            if (smooth.enabled() == null) {
                errors.add(new FieldError("smooth.enabled", "是否启用 S 曲线不能为空"));
            } else {
                smoothEnabled = smooth.enabled();
            }
            if (smoothEnabled) {
                Double tau = smooth.tauMs();
                tauMs = tau == null ? DEFAULT_TAU_MS : tau;
                if (!isFinite(tauMs) || tauMs <= 0 || tauMs > 1000) {
                    errors.add(new FieldError("smooth.tauMs", "平滑时间必须大于 0 且不超过 1000 ms"));
                }
            }
        }

        CalculationRequest.Plan plan = request.plan();
        double load = 0;
        double distance = 0;
        Double time = null;
        double dwell = DEFAULT_DWELL;
        Double maxVelocity = null;
        Double maxAcceleration = null;
        double resistance = DEFAULT_RESISTANCE;
        double tilt = DEFAULT_TILT;
        double ambientTemp = DEFAULT_AMBIENT_TEMP;
        Double supplyVoltage = null;

        if (plan == null) {
            errors.add(new FieldError("plan", "工况输入不能为空"));
        } else {
            if (!isFinite(plan.load()) || plan.load() < 0) {
                errors.add(new FieldError("plan.load", "负载必须为非负数"));
            } else {
                load = plan.load();
            }

            if (!isFinite(plan.distance()) || plan.distance() <= 0) {
                errors.add(new FieldError("plan.distance", "运动距离必须大于 0"));
            } else {
                distance = plan.distance();
            }

            if (mode == MotionMode.POINT_TO_POINT) {
                if (!isFinite(plan.time()) || plan.time() <= 0) {
                    errors.add(new FieldError("plan.time", "点到点模式必须给出大于 0 的运动时间"));
                } else {
                    time = plan.time();
                }
            } else {
                if (plan.time() != null && (!isFinite(plan.time()) || plan.time() <= 0)) {
                    errors.add(new FieldError("plan.time", "运动时间若填写必须大于 0"));
                } else {
                    time = plan.time();
                }
            }

            if (plan.dwell() != null) {
                if (!isFinite(plan.dwell()) || plan.dwell() < 0) {
                    errors.add(new FieldError("plan.dwell", "停顿时间必须为非负数"));
                } else {
                    dwell = plan.dwell();
                }
            }

            if (plan.maxVelocity() != null) {
                if (!isFinite(plan.maxVelocity()) || plan.maxVelocity() <= 0) {
                    errors.add(new FieldError("plan.maxVelocity", "最大速度必须大于 0"));
                } else {
                    maxVelocity = plan.maxVelocity();
                }
            }

            if (plan.maxAcceleration() != null) {
                if (!isFinite(plan.maxAcceleration()) || plan.maxAcceleration() <= 0) {
                    errors.add(new FieldError("plan.maxAcceleration", "最大加速度必须大于 0"));
                } else {
                    maxAcceleration = plan.maxAcceleration();
                }
            }

            if (plan.resistance() != null) {
                if (!isFinite(plan.resistance())) {
                    errors.add(new FieldError("plan.resistance", "阻力必须为有效数值"));
                } else if (axis == AxisKind.ROTARY && plan.resistance() < 0) {
                    errors.add(new FieldError("plan.resistance", "旋转电机阻力矩必须为非负数"));
                } else {
                    resistance = plan.resistance();
                }
            }

            if (plan.tiltDeg() != null) {
                if (!isFinite(plan.tiltDeg()) || plan.tiltDeg() < 0 || plan.tiltDeg() > 360) {
                    errors.add(new FieldError("plan.tiltDeg", "倾斜角必须在 0–360 之间"));
                } else {
                    tilt = plan.tiltDeg();
                }
            }

            if (plan.ambientTemp() != null) {
                if (!isFinite(plan.ambientTemp()) || plan.ambientTemp() <= -273) {
                    errors.add(new FieldError("plan.ambientTemp", "环境温度必须大于 -273 °C"));
                } else {
                    ambientTemp = plan.ambientTemp();
                }
            }

            if (plan.supplyVoltage() != null) {
                if (!isFinite(plan.supplyVoltage()) || plan.supplyVoltage() < 0) {
                    errors.add(new FieldError("plan.supplyVoltage", "供电母线电压必须为非负数"));
                } else {
                    supplyVoltage = plan.supplyVoltage();
                }
            }
        }

        double lowerMargin = DEFAULT_LOWER_MARGIN;
        double upperMargin = DEFAULT_UPPER_MARGIN;
        boolean upperEnabled = false;
        CalculationRequest.Margin margin = request.margin();
        if (margin != null) {
            if (margin.lowerPercent() != null) {
                if (!isFinite(margin.lowerPercent())
                        || margin.lowerPercent() < 0
                        || margin.lowerPercent() > 100
                        || !isStep(margin.lowerPercent(), 5)) {
                    errors.add(new FieldError("margin.lowerPercent", "安全余量下限必须在 0–100 且为 5 的倍数"));
                } else {
                    lowerMargin = margin.lowerPercent();
                }
            }
            if (margin.upperPercent() != null) {
                if (!isFinite(margin.upperPercent())
                        || margin.upperPercent() < 100
                        || margin.upperPercent() > 500
                        || !isStep(margin.upperPercent(), 25)) {
                    errors.add(new FieldError("margin.upperPercent", "安全余量上限必须在 100–500 且为 25 的倍数"));
                } else {
                    upperMargin = margin.upperPercent();
                }
            }
            if (margin.upperEnabled() != null) {
                upperEnabled = margin.upperEnabled();
            }
        }

        List<String> structures = new ArrayList<>();
        List<String> coolings = new ArrayList<>();
        CalculationRequest.Filters filters = request.filters();
        if (filters != null) {
            validateFilterValues(filters.structures(), "filters.structures", axis, true, structures, errors);
            validateFilterValues(filters.coolings(), "filters.coolings", axis, false, coolings, errors);
        }

        Long selectedMotorId = request.selectedMotorId();
        if (selectedMotorId != null && selectedMotorId < 1) {
            errors.add(new FieldError("selectedMotorId", "电机 id 必须大于 0"));
        }

        if (!errors.isEmpty()) {
            throw validationError(errors);
        }

        return new CalculationInput(
                axis,
                mode,
                shape,
                smoothEnabled,
                tauMs,
                load,
                distance,
                time,
                dwell,
                maxVelocity,
                maxAcceleration,
                resistance,
                tilt,
                ambientTemp,
                supplyVoltage,
                lowerMargin / 100,
                upperMargin,
                upperEnabled,
                structures,
                coolings,
                selectedMotorId);
    }

    private static AxisKind parseAxis(String code, List<FieldError> errors) {
        if (code == null || code.isBlank()) {
            errors.add(new FieldError("axisKind", "页签类型不能为空"));
            return AxisKind.LINEAR;
        }
        try {
            return AxisKind.fromCode(code);
        } catch (IllegalArgumentException ex) {
            errors.add(new FieldError("axisKind", "页签类型只能为 linear / rotary"));
            return AxisKind.LINEAR;
        }
    }

    private static MotionMode parseMode(String code, List<FieldError> errors) {
        if ("pointToPoint".equals(code)) {
            return MotionMode.POINT_TO_POINT;
        }
        if ("custom".equals(code)) {
            return MotionMode.CUSTOM;
        }
        errors.add(new FieldError("mode", "运动模式只能为 pointToPoint / custom"));
        return MotionMode.POINT_TO_POINT;
    }

    private static CurveShape parseShape(String code, List<FieldError> errors) {
        if (code == null || code.isBlank()) {
            return CurveShape.TRAPEZOID;
        }
        try {
            return CurveShape.fromCode(code);
        } catch (IllegalArgumentException ex) {
            errors.add(new FieldError("shape", "曲线形状只能为 trapezoid / triangle"));
            return CurveShape.TRAPEZOID;
        }
    }

    private static void validateFilterValues(
            List<String> values,
            String field,
            AxisKind axis,
            boolean structure,
            List<String> target,
            List<FieldError> errors) {

        if (values == null) {
            return;
        }
        if (values.size() > MAX_FILTER_VALUES) {
            errors.add(new FieldError(field, "筛选项不能超过 " + MAX_FILTER_VALUES + " 个"));
            return;
        }

        Set<String> allowed = allowedValues(axis, structure);
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value == null || value.isBlank()) {
                errors.add(new FieldError(field + "[" + index + "]", "筛选项不能为空"));
            } else if (!allowed.contains(value.trim())) {
                errors.add(new FieldError(field + "[" + index + "]", "筛选项不在当前页签的模板范围内"));
            } else {
                target.add(value.trim());
            }
        }
    }

    private static Set<String> allowedValues(AxisKind axis, boolean structure) {
        Set<String> allowed = new HashSet<>();
        List<MotorType> types = axis == AxisKind.LINEAR
                ? List.of(MotorType.LINEAR, MotorType.COIL)
                : List.of(MotorType.TORQUE);
        for (MotorType type : types) {
            MotorTypeTemplate template = MotorCatalogTemplates.of(type);
            allowed.addAll(structure ? template.structures() : template.coolings());
        }
        return allowed;
    }

    private static BusinessException validationError(List<FieldError> errors) {
        return new BusinessException(
                ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败", errors);
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static boolean isStep(double value, double step) {
        return Math.abs(value / step - Math.rint(value / step)) < 1e-9;
    }
}
