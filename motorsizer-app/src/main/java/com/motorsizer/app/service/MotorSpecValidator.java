package com.motorsizer.app.service;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.common.FieldError;
import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import com.motorsizer.domain.catalog.SpecLimits;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C3/C4 校验（04 C3 规则 1–8）。必填集合、字段白名单与业务上限全部取自 domain 的 K2 模板与
 * {@link SpecLimits}，不在此处硬编码（C-B 单一来源）。
 */
@Component
public class MotorSpecValidator {

    private static final Set<String> CONFIG_NAMES = Set.of("默认", "串联", "并联");
    private static final Set<String> ENTRY_METHODS = Set.of("手工录入", "识别草稿");

    /**
     * 校验并返回电机类型与规整后的规格数值；校验失败抛出业务异常。
     *
     * @param existingType C4 传入库中类型（不允许变更）；C3 传 {@code null}
     * @param existingSpec C4 传入库中现有规格（用于「录入必填」的「原本就缺就放过」）；
     *                     C3 传 {@code null}
     */
    public ValidatedMotor validate(MotorUpsertRequest request, MotorType existingType) {
        return validate(request, existingType, null);
    }

    /**
     * 带「原规格」的校验：编辑老电机时，本来就没有的「录入必填」项不再强制补。
     *
     * <p>库里 2026-11-20 之前录的电机基本都没有线圈质量/转动惯量，若一律强制，
     * 用户改一个无关字段也会被「缺质量」挡住。口径是「不动它就放过」：原来有值、
     * 本次被清空 → 报错；原来就没有、本次也没填 → 放过。
     */
    public ValidatedMotor validate(MotorUpsertRequest request, MotorType existingType,
                                   Map<String, BigDecimal> existingSpec) {
        MotorType type = resolveType(request, existingType);
        MotorTypeTemplate template = MotorCatalogTemplates.of(type);

        List<FieldError> commonErrors = validateCommon(request, template);
        if (!commonErrors.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败", commonErrors);
        }

        Map<String, BigDecimal> spec = validateSpec(type, template, request.spec(), existingSpec);
        return new ValidatedMotor(type, spec);
    }

    /** 校验结果：类型 + 全部数值已规整为内部单位。 */
    public record ValidatedMotor(MotorType type, Map<String, BigDecimal> spec) {
    }

    private MotorType resolveType(MotorUpsertRequest request, MotorType existingType) {
        String raw = trim(request.motorType());
        if (raw.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                    List.of(new FieldError("motorType", "电机类型必填")));
        }
        MotorType type = MotorType.fromCode(raw);
        if (type == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                    List.of(new FieldError("motorType", "电机类型只能为 linear / torque / coil")));
        }
        if (existingType != null && existingType != type) {
            // C4：不允许改类型（改类型等于换规格表，属结构变更）
            throw new BusinessException(ErrorCode.MOTOR_TYPE_MISMATCH,
                    "不允许修改电机类型：" + existingType.code() + " → " + type.code());
        }
        return type;
    }

    private List<FieldError> validateCommon(MotorUpsertRequest request, MotorTypeTemplate template) {
        List<FieldError> errors = new ArrayList<>();

        String modelName = trim(request.modelName());
        if (modelName.isEmpty()) {
            errors.add(new FieldError("modelName", "型号名必填"));
        } else if (modelName.length() > 100) {
            errors.add(new FieldError("modelName", "型号名长度不能超过 100"));
        }

        String configName = trim(request.configName());
        if (configName.isEmpty()) {
            errors.add(new FieldError("configName", "配置名必填"));
        } else if (!CONFIG_NAMES.contains(configName)) {
            errors.add(new FieldError("configName", "配置名只能为 默认 / 串联 / 并联"));
        }

        String structure = trim(request.structure());
        if (structure.isEmpty()) {
            errors.add(new FieldError("structure", "结构类型必填"));
        } else if (!template.allowsStructure(structure)) {
            errors.add(new FieldError("structure", "结构类型不在该电机类型的可选范围内: " + structure));
        }

        String cooling = trim(request.cooling());
        if (cooling.isEmpty()) {
            errors.add(new FieldError("cooling", "冷却方式必填"));
        } else if (!template.allowsCooling(cooling)) {
            errors.add(new FieldError("cooling", "冷却方式不在该电机类型的可选范围内: " + cooling));
        }

        String bore = trim(request.bore());
        if (!bore.isEmpty()) {
            if (template.type() != MotorType.TORQUE) {
                errors.add(new FieldError("bore", "直线/音圈电机不允许填写中孔规格"));
            } else if (!template.allowsBore(bore)) {
                errors.add(new FieldError("bore", "中孔规格只能为 标准 / 特殊"));
            }
        }

        String entryMethod = trim(request.entryMethod());
        if (entryMethod.isEmpty()) {
            errors.add(new FieldError("entryMethod", "录入方式必填"));
        } else if (!ENTRY_METHODS.contains(entryMethod)) {
            errors.add(new FieldError("entryMethod", "录入方式只能为 手工录入 / 识别草稿"));
        }

        if (request.enteredOn() != null && request.enteredOn().isAfter(LocalDate.now())) {
            errors.add(new FieldError("enteredOn", "录入日期不能晚于今天"));
        }

        addLengthError(errors, "manufacturer", trim(request.manufacturer()), 50);
        addLengthError(errors, "series", trim(request.series()), 50);
        addLengthError(errors, "sourceDoc", trim(request.sourceDoc()), 200);
        addLengthError(errors, "sourcePage", trim(request.sourcePage()), 20);
        addLengthError(errors, "note", trim(request.note()), 1000);

        if (request.spec() == null) {
            errors.add(new FieldError("spec", "规格参数必填"));
        }
        return errors;
    }

    private void addLengthError(List<FieldError> errors, String field, String value, int max) {
        if (value.length() > max) {
            errors.add(new FieldError(field, "长度不能超过 " + max));
        }
    }

    /**
     * 规则 1–8：白名单、必填、数值形态与业务范围。
     */
    private Map<String, BigDecimal> validateSpec(
            MotorType type, MotorTypeTemplate template, Map<String, Object> rawSpec,
            Map<String, BigDecimal> existingSpec) {

        Map<String, Object> raw = rawSpec == null ? Map.of() : rawSpec;
        Set<String> allowed = template.allowedKeys();

        List<FieldError> typeErrors = new ArrayList<>();
        List<FieldError> numberErrors = new ArrayList<>();
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 规则 2：字段白名单（含 spec.derived 这类嵌套对象）
            if (!allowed.contains(key)) {
                typeErrors.add(new FieldError("spec." + key, "该字段不属于 " + type.code() + " 的规格模板"));
                continue;
            }
            // 规则 5：null 与省略等价
            if (value == null) {
                continue;
            }
            if (value instanceof Map || value instanceof List) {
                typeErrors.add(new FieldError("spec." + key, "规格字段必须为数值，不能为对象或数组"));
                continue;
            }
            BigDecimal number = toDecimal(value);
            if (number == null) {
                numberErrors.add(new FieldError("spec." + key, "规格字段必须为数值"));
                continue;
            }
            // 规则 6：业务范围与下限
            MotorField field = template.field(key).orElseThrow();
            SpecLimits.maxOf(key).ifPresent(max -> {
                if (number.abs().compareTo(max) > 0) {
                    numberErrors.add(new FieldError("spec." + key,
                            "超出业务上限（±" + max.stripTrailingZeros().toPlainString() + "）"));
                }
            });
            if (SpecLimits.mustBePositive(key) && number.signum() <= 0) {
                numberErrors.add(new FieldError("spec." + key, field.label() + "必须大于 0"));
            } else if (number.signum() < 0) {
                numberErrors.add(new FieldError("spec." + key, field.label() + "不能为负数"));
            }
            normalized.put(key, number);
        }

        if (!typeErrors.isEmpty()) {
            throw new BusinessException(ErrorCode.MOTOR_TYPE_MISMATCH,
                    "规格结构与电机类型不匹配", typeErrors);
        }

        // 规则 1：必填 = K2 req 集合，一次列出全部缺项
        List<FieldError> missing = new ArrayList<>(template.requiredKeys().stream()
                .filter(key -> !normalized.containsKey(key))
                .map(key -> new FieldError("spec." + key,
                        template.field(key).map(MotorField::label).orElse(key) + "必填"))
                .toList());
        // 录入必填（直线/音圈的线圈质量、力矩的转动惯量，2026-11-20 用户裁定）。
        // 新建必须填；编辑时「原本就缺」的放过，不逼用户为了改别的字段补历史数据。
        for (String key : MotorCatalogTemplates.entryRequiredKeys(type)) {
            if (normalized.containsKey(key)) {
                continue;
            }
            if (existingSpec != null && !existingSpec.containsKey(key)) {
                continue;
            }
            missing.add(new FieldError("spec." + key,
                    template.field(key).map(MotorField::label).orElse(key) + "必填"));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.MOTOR_SPEC_REQUIRED, "必填规格字段缺失", missing);
        }
        if (!numberErrors.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败", numberErrors);
        }
        return normalized;
    }

    /** Jackson 数字默认是 Integer/Long/Double；统一收敛为 BigDecimal。字符串不算数值。 */
    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
