package com.motorsizer.app.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.common.FieldError;
import com.motorsizer.app.common.PageResult;
import com.motorsizer.app.dto.CalculationMotor;
import com.motorsizer.app.dto.MotorDetailDto;
import com.motorsizer.app.dto.MotorListItemDto;
import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.app.entity.MotorCoilSpecEntity;
import com.motorsizer.app.entity.MotorEntity;
import com.motorsizer.app.entity.MotorLinearSpecEntity;
import com.motorsizer.app.entity.MotorTorqueSpecEntity;
import com.motorsizer.app.mapper.MotorCoilSpecMapper;
import com.motorsizer.app.mapper.MotorLinearSpecMapper;
import com.motorsizer.app.mapper.MotorMapper;
import com.motorsizer.app.mapper.MotorTorqueSpecMapper;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorDerived;
import com.motorsizer.domain.catalog.MotorDerivedCalculator;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.MotorParameters;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1 电机库服务：A1 之外的 C1–C4。
 *
 * <p>事务边界（04 C3）：校验 → 唯一性 → 写主表 → 按类型写对应规格表，任一失败整体回滚。
 * 数据库无物理外键，「类型 ↔ 规格表」一致性只能由本层保证。
 */
@Service
public class MotorService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final MotorMapper motorMapper;
    private final MotorLinearSpecMapper linearSpecMapper;
    private final MotorTorqueSpecMapper torqueSpecMapper;
    private final MotorCoilSpecMapper coilSpecMapper;
    private final MotorSpecValidator validator;

    public MotorService(MotorMapper motorMapper,
                        MotorLinearSpecMapper linearSpecMapper,
                        MotorTorqueSpecMapper torqueSpecMapper,
                        MotorCoilSpecMapper coilSpecMapper,
                        MotorSpecValidator validator) {
        this.motorMapper = motorMapper;
        this.linearSpecMapper = linearSpecMapper;
        this.torqueSpecMapper = torqueSpecMapper;
        this.coilSpecMapper = coilSpecMapper;
        this.validator = validator;
    }

    // ---------------------------------------------------------------- C1 列表

    public PageResult<MotorListItemDto> list(int page, int size, String motorType,
                                             String keyword, String structure, String cooling) {
        int safePage = page <= 0 ? 1 : page;
        int safeSize = size <= 0 ? DEFAULT_SIZE : size;
        if (safeSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                    List.of(new FieldError("size", "页大小不能超过 " + MAX_SIZE)));
        }

        QueryWrapper<MotorEntity> wrapper = new QueryWrapper<>();
        if (hasText(motorType)) {
            MotorType type = MotorType.fromCode(motorType);
            if (type == null) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                        List.of(new FieldError("motorType", "电机类型只能为 linear / torque / coil")));
            }
            wrapper.eq("motor_type", type.code());
            var template = MotorCatalogTemplates.of(type);
            if (hasText(structure) && !template.allowsStructure(structure)) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                        List.of(new FieldError("structure", "结构类型不在该电机类型的可选范围内")));
            }
            if (hasText(cooling) && !template.allowsCooling(cooling)) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                        List.of(new FieldError("cooling", "冷却方式不在该电机类型的可选范围内")));
            }
        }
        if (hasText(structure)) {
            wrapper.eq("structure", structure.trim());
        }
        if (hasText(cooling)) {
            wrapper.eq("cooling", cooling.trim());
        }
        if (hasText(keyword)) {
            // H2 的 LIKE 区分大小写，必须显式 LOWER（04 §1.7）；条目为百条级，全表扫描可接受
            String like = "%" + keyword.trim().toLowerCase() + "%";
            wrapper.and(w -> w.apply("LOWER(model_name) LIKE {0}", like)
                    .or().apply("LOWER(series) LIKE {0}", like));
        }
        wrapper.orderByAsc("motor_type", "model_name", "config_name");

        Page<MotorEntity> paged = motorMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<MotorListItemDto> items = paged.getRecords().stream().map(this::toListItem).toList();
        return PageResult.of(safePage, safeSize, paged.getTotal(), items);
    }

    // ---------------------------------------------------------------- C2 详情

    public MotorDetailDto get(Long id) {
        MotorEntity motor = requireMotor(id);
        MotorType type = requireType(motor);
        Object specEntity = loadSpec(type, motor.getId());

        Map<String, Object> spec = MotorSpecConverter.toSpecMap(type, specEntity);
        Map<String, java.math.BigDecimal> numeric = new java.util.LinkedHashMap<>();
        spec.forEach((key, value) -> {
            if (value instanceof java.math.BigDecimal decimal) {
                numeric.put(key, decimal);
            }
        });
        MotorDerived derived = MotorDerivedCalculator.compute(type, numeric);
        spec.put("derived", derived);

        return new MotorDetailDto(
                motor.getId(), motor.getModelName(), motor.getConfigName(), motor.getMotorType(),
                motor.getStructure(), motor.getCooling(), motor.getBore(),
                motor.getManufacturer(), motor.getSeries(), motor.getSourceDoc(), motor.getSourcePage(),
                motor.getEntryMethod(), motor.getEnteredOn(), motor.getNote(),
                motor.getCreatedAt(), motor.getUpdatedAt(), spec);
    }

    // ---------------------------------------------------------------- C3 新增

    @Transactional
    public Long create(MotorUpsertRequest request) {
        MotorSpecValidator.ValidatedMotor validated = validator.validate(request, null);
        MotorType type = validated.type();

        ensureUnique(request.modelName().trim(), request.configName().trim(), null);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MotorEntity motor = new MotorEntity();
        applyCommonFields(motor, request);
        motor.setCreatedAt(now);
        motor.setUpdatedAt(now);

        try {
            motorMapper.insert(motor);
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }

        insertSpec(type, motor.getId(), validated.spec(), now);
        return motor.getId();
    }

    // ---------------------------------------------------------------- 主键查询

    /**
     * 按主键（型号名 + 配置名）取电机 id；不存在返回 {@code null}。
     *
     * <p>供样册入库判断「库里已有 → 覆盖」用（06 §6.1）。只读，不改变写库路径：
     * 写入仍必须经 {@link #create} / {@link #update}，校验与事务不外溢。
     */
    public Long findId(String modelName, String configName) {
        if (!hasText(modelName) || !hasText(configName)) {
            return null;
        }
        MotorEntity motor = motorMapper.selectOne(new QueryWrapper<MotorEntity>()
                .eq("model_name", modelName.trim())
                .eq("config_name", configName.trim()));
        return motor == null ? null : motor.getId();
    }

    // ---------------------------------------------------------------- C4 编辑

    @Transactional
    public void update(Long id, MotorUpsertRequest request) {
        MotorEntity existing = requireMotor(id);
        MotorType existingType = requireType(existing);

        MotorSpecValidator.ValidatedMotor validated =
                validator.validate(request, existingType, existingSpecOf(existingType, id));
        ensureUnique(request.modelName().trim(), request.configName().trim(), id);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        applyCommonFields(existing, request);
        // createdAt 保持原值（null 字段不参与 UPDATE）
        existing.setCreatedAt(null);
        existing.setUpdatedAt(now);

        try {
            motorMapper.updateById(existing);
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }

        updateSpec(validated.type(), id, validated.spec(), now);
    }

    // ---------------------------------------------------------------- 清空数据库

    /**
     * 清空电机库全部数据（4 张表）。先删三张规格表，再删主表；
     * 无物理外键，「类型 ↔ 规格表」一致性由本层保证。事务内整体回滚。
     *
     * @return 清空的主表记录数
     */
    @Transactional
    public int clearAll() {
        linearSpecMapper.delete(null);
        torqueSpecMapper.delete(null);
        coilSpecMapper.delete(null);
        return motorMapper.delete(null);
    }

    // ---------------------------------------------------------------- S3 计算池

    /**
     * 计算用候选池：只取与当前页签同量纲的电机（直线页 = linear + coil，旋转页 = torque），
     * 结构/冷却过滤在 SQL 层完成；规格表按类型批量加载，避免 N+1。
     */
    public List<CalculationMotor> loadCalculationPool(
            AxisKind axis, List<String> structures, List<String> coolings) {
        List<String> typeCodes = axis == AxisKind.LINEAR
                ? List.of(MotorType.LINEAR.code(), MotorType.COIL.code())
                : List.of(MotorType.TORQUE.code());

        QueryWrapper<MotorEntity> wrapper = new QueryWrapper<>();
        wrapper.in("motor_type", typeCodes);
        // 第 67/68 轮：两个页签的 structures 都是「系列」前缀，无法用 SQL 的 in 表达式
        // 表达（含「其他」的排除法），下放到内存按型号名匹配。
        boolean seriesFilter = structures != null && !structures.isEmpty();
        // 第 69 轮：「结构」（冷却方式）也不能用 SQL 的 in —— 库里的字段值是「普通/气冷/水冷」，
        // 而筛选值有「自然冷却」，且要给字段为空的电机按型号名兜底。统一走 MotorCooling。
        boolean coolingFilter = coolings != null && !coolings.isEmpty();
        wrapper.orderByAsc("motor_type", "model_name", "config_name");

        List<MotorEntity> motors = motorMapper.selectList(wrapper);
        if (motors.isEmpty()) {
            return List.of();
        }
        List<CalculationMotor> assembled = assembleCalculationMotors(motors);
        if (coolingFilter) {
            assembled = assembled.stream()
                    .filter(motor -> coolings.contains(
                            MotorCooling.of(motor.modelName(), motor.cooling())))
                    .toList();
        }
        if (!seriesFilter) {
            return assembled;
        }
        List<String> known = MotorSeries.forAxis(axis);
        return assembled.stream()
                .filter(motor -> structures.stream()
                        .anyMatch(s -> MotorSeries.matches(motor.modelName(), s, known)))
                .toList();
    }

    /** 按 id 取单台电机（用于 selectedMotorId）；不存在抛 404。 */
    public CalculationMotor loadCalculationMotor(Long id) {
        MotorEntity motor = requireMotor(id);
        MotorType type = requireType(motor);
        Object specEntity = loadSpec(type, motor.getId());
        return toCalculationMotor(motor, type, specEntity);
    }

    private List<CalculationMotor> assembleCalculationMotors(List<MotorEntity> motors) {
        Map<MotorType, List<MotorEntity>> byType = new EnumMap<>(MotorType.class);
        for (MotorEntity motor : motors) {
            MotorType type = requireType(motor);
            byType.computeIfAbsent(type, key -> new ArrayList<>()).add(motor);
        }

        Map<Long, Object> specEntities = new HashMap<>();
        byType.forEach((type, typeMotors) -> {
            List<Long> ids = typeMotors.stream().map(MotorEntity::getId).toList();
            switch (type) {
                case LINEAR -> linearSpecMapper.selectList(
                                new QueryWrapper<MotorLinearSpecEntity>().in("motor_id", ids))
                        .forEach(spec -> specEntities.put(spec.getMotorId(), spec));
                case TORQUE -> torqueSpecMapper.selectList(
                                new QueryWrapper<MotorTorqueSpecEntity>().in("motor_id", ids))
                        .forEach(spec -> specEntities.put(spec.getMotorId(), spec));
                case COIL -> coilSpecMapper.selectList(
                                new QueryWrapper<MotorCoilSpecEntity>().in("motor_id", ids))
                        .forEach(spec -> specEntities.put(spec.getMotorId(), spec));
            }
        });

        List<CalculationMotor> result = new ArrayList<>(motors.size());
        for (MotorEntity motor : motors) {
            MotorType type = MotorType.fromCode(motor.getMotorType());
            Object specEntity = specEntities.get(motor.getId());
            if (type == null || specEntity == null) {
                throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND,
                        "电机记录缺少规格数据: " + motor.getId());
            }
            result.add(toCalculationMotor(motor, type, specEntity));
        }
        return result;
    }

    private CalculationMotor toCalculationMotor(MotorEntity motor, MotorType type, Object specEntity) {
        Map<String, Object> rawSpec = MotorSpecConverter.toSpecMap(type, specEntity);
        Map<String, BigDecimal> numericSpec = new LinkedHashMap<>();
        rawSpec.forEach((key, value) -> {
            if (value instanceof BigDecimal decimal) {
                numericSpec.put(key, decimal);
            }
        });

        MotorDerived derived = MotorDerivedCalculator.compute(type, numericSpec);

        Map<String, Double> doubles = new LinkedHashMap<>();
        numericSpec.forEach((key, value) -> doubles.put(key, value.doubleValue()));

        return new CalculationMotor(
                motor.getId(),
                motor.getModelName(),
                motor.getConfigName(),
                type,
                motor.getStructure(),
                motor.getCooling(),
                numericSpec,
                derived,
                MotorParameters.of(type, doubles));
    }

    // ---------------------------------------------------------------- 内部

    private void applyCommonFields(MotorEntity target, MotorUpsertRequest request) {
        target.setModelName(request.modelName().trim());
        target.setConfigName(request.configName().trim());
        target.setMotorType(request.motorType().trim());
        target.setStructure(request.structure().trim());
        target.setCooling(request.cooling().trim());
        target.setBore(trimToNull(request.bore()));
        target.setManufacturer(trimToNull(request.manufacturer()));
        target.setSeries(trimToNull(request.series()));
        target.setSourceDoc(trimToNull(request.sourceDoc()));
        target.setSourcePage(trimToNull(request.sourcePage()));
        target.setEntryMethod(request.entryMethod().trim());
        target.setEnteredOn(request.enteredOn());
        target.setNote(trimToNull(request.note()));
    }

    private MotorEntity requireMotor(Long id) {
        if (id == null || id < 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED, "参数校验失败",
                    List.of(new FieldError("id", "电机 id 必须大于 0")));
        }
        MotorEntity motor = motorMapper.selectById(id);
        if (motor == null) {
            throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND, "电机记录不存在: " + id);
        }
        return motor;
    }

    private MotorType requireType(MotorEntity motor) {
        MotorType type = MotorType.fromCode(motor.getMotorType());
        if (type == null) {
            // 数据库 CHECK 已限制取值；出现即属数据损坏，按类型不匹配报错而不是 500
            throw new BusinessException(ErrorCode.MOTOR_TYPE_MISMATCH,
                    "电机记录的类型非法: " + motor.getMotorType());
        }
        return type;
    }

    private void ensureUnique(String modelName, String configName, Long excludeId) {
        QueryWrapper<MotorEntity> wrapper = new QueryWrapper<MotorEntity>()
                .eq("model_name", modelName)
                .eq("config_name", configName);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (motorMapper.selectCount(wrapper) > 0) {
            throw duplicate();
        }
    }

    private BusinessException duplicate() {
        return new BusinessException(ErrorCode.MOTOR_DUPLICATE,
                "型号名与配置名已存在，请改用其他型号名或配置");
    }

    private void insertSpec(MotorType type, Long motorId,
                            Map<String, java.math.BigDecimal> spec, LocalDateTime now) {
        switch (type) {
            case LINEAR -> linearSpecMapper.insert(MotorSpecConverter.toLinearEntity(motorId, spec, now));
            case TORQUE -> torqueSpecMapper.insert(MotorSpecConverter.toTorqueEntity(motorId, spec, now));
            case COIL -> coilSpecMapper.insert(MotorSpecConverter.toCoilEntity(motorId, spec, now));
        }
    }

    private void updateSpec(MotorType type, Long motorId,
                            Map<String, java.math.BigDecimal> spec, LocalDateTime now) {
        switch (type) {
            case LINEAR -> {
                MotorLinearSpecEntity entity = MotorSpecConverter.toLinearEntity(motorId, spec, now);
                entity.setId(requireSpecId(type, motorId));
                entity.setCreatedAt(null);
                linearSpecMapper.updateById(entity);
            }
            case TORQUE -> {
                MotorTorqueSpecEntity entity = MotorSpecConverter.toTorqueEntity(motorId, spec, now);
                entity.setId(requireSpecId(type, motorId));
                entity.setCreatedAt(null);
                torqueSpecMapper.updateById(entity);
            }
            case COIL -> {
                MotorCoilSpecEntity entity = MotorSpecConverter.toCoilEntity(motorId, spec, now);
                entity.setId(requireSpecId(type, motorId));
                entity.setCreatedAt(null);
                coilSpecMapper.updateById(entity);
            }
        }
    }

    /** 库里现有的规格数值（供「录入必填」判断「原本就缺就放过」，不逼用户补历史数据）。 */
    private Map<String, java.math.BigDecimal> existingSpecOf(MotorType type, Long motorId) {
        Object entity = switch (type) {
            case LINEAR -> linearSpecMapper.selectOne(
                    new QueryWrapper<MotorLinearSpecEntity>().eq("motor_id", motorId));
            case TORQUE -> torqueSpecMapper.selectOne(
                    new QueryWrapper<MotorTorqueSpecEntity>().eq("motor_id", motorId));
            case COIL -> coilSpecMapper.selectOne(
                    new QueryWrapper<MotorCoilSpecEntity>().eq("motor_id", motorId));
        };
        if (entity == null) {
            return Map.of();
        }
        Map<String, java.math.BigDecimal> spec = new LinkedHashMap<>();
        MotorSpecConverter.toSpecMap(type, entity).forEach((key, value) -> {
            if (value instanceof java.math.BigDecimal decimal) {
                spec.put(key, decimal);
            }
        });
        return spec;
    }

    /** 规格行应始终存在（C3 同事务写入）；缺失视为历史数据损坏。 */
    private Long requireSpecId(MotorType type, Long motorId) {
        QueryWrapper<MotorLinearSpecEntity> linear = new QueryWrapper<MotorLinearSpecEntity>()
                .eq("motor_id", motorId);
        switch (type) {
            case LINEAR -> {
                MotorLinearSpecEntity entity = linearSpecMapper.selectOne(linear);
                if (entity == null) {
                    throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND,
                            "电机记录缺少直线规格数据: " + motorId);
                }
                return entity.getId();
            }
            case TORQUE -> {
                MotorTorqueSpecEntity entity = torqueSpecMapper.selectOne(
                        new QueryWrapper<MotorTorqueSpecEntity>().eq("motor_id", motorId));
                if (entity == null) {
                    throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND,
                            "电机记录缺少力矩规格数据: " + motorId);
                }
                return entity.getId();
            }
            case COIL -> {
                MotorCoilSpecEntity entity = coilSpecMapper.selectOne(
                        new QueryWrapper<MotorCoilSpecEntity>().eq("motor_id", motorId));
                if (entity == null) {
                    throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND,
                            "电机记录缺少音圈规格数据: " + motorId);
                }
                return entity.getId();
            }
            default -> throw new IllegalStateException("未知类型: " + type);
        }
    }

    private Object loadSpec(MotorType type, Long motorId) {
        switch (type) {
            case LINEAR -> {
                MotorLinearSpecEntity entity = linearSpecMapper.selectOne(
                        new QueryWrapper<MotorLinearSpecEntity>().eq("motor_id", motorId));
                if (entity == null) {
                    throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND,
                            "电机记录缺少直线规格数据: " + motorId);
                }
                return entity;
            }
            case TORQUE -> {
                MotorTorqueSpecEntity entity = torqueSpecMapper.selectOne(
                        new QueryWrapper<MotorTorqueSpecEntity>().eq("motor_id", motorId));
                if (entity == null) {
                    throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND,
                            "电机记录缺少力矩规格数据: " + motorId);
                }
                return entity;
            }
            case COIL -> {
                MotorCoilSpecEntity entity = coilSpecMapper.selectOne(
                        new QueryWrapper<MotorCoilSpecEntity>().eq("motor_id", motorId));
                if (entity == null) {
                    throw new BusinessException(ErrorCode.MOTOR_NOT_FOUND,
                            "电机记录缺少音圈规格数据: " + motorId);
                }
                return entity;
            }
            default -> throw new IllegalStateException("未知类型: " + type);
        }
    }

    private MotorListItemDto toListItem(MotorEntity motor) {
        return new MotorListItemDto(
                motor.getId(), motor.getModelName(), motor.getConfigName(), motor.getMotorType(),
                motor.getStructure(), motor.getCooling(), motor.getBore(),
                motor.getManufacturer(), motor.getSeries(), motor.getEntryMethod(),
                motor.getEnteredOn(), motor.getUpdatedAt());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
