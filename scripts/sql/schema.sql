-- ============================================================================
-- MotorSizer 数据库建表脚本
-- 目标库：H2 文件模式（jdbc:h2:file:...;MODE=MySQL;AUTO_SERVER=TRUE）
--
-- 设计依据：
--   doc/design/01.功能模块梳理.md  （只建电机域；工程数据不入库）
--   doc/design/02.整体架构设计.md  （H2 文件模式、MyBatis-Plus、字段字典在代码内）
--   doc/requirements.md            （第 6 章电机库、第 63 轮裁定）
--   doc/rules/database-rules.md    （命名/类型/约束规范）
--
-- 遵循规范：
--   R2-1 snake_case 命名；R2-3 表名单数风格统一；R2-4 主键 id、关联实体 <entity>_id
--   R2-5 时间字段 created_at / updated_at
--   R3-2 必填字段 NOT NULL；R3-3 不设掩盖缺失的默认值
--   R3-4 数值使用定点类型 DECIMAL；R3-5 时间统一存 UTC
--   R3-6 状态字段取值集有限且用 CHECK 约束
--   R4-1 自增主键；R4-2 不建物理外键；R4-3 逻辑关联由应用层维护
--   R4-4 业务唯一值使用唯一约束；R4-5 服务端校验 + 数据库约束双重防线
--
-- 幂等性：全部使用 IF NOT EXISTS，可重复执行（首次启动自动建表）。
-- 说明：本脚本不插入任何业务数据（Q3=A：产品首次启动为空库）。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 表 1：motor —— 电机主表（公共标识与属性）
-- 一行 = 一台可完整计算的电机，业务主键 = (model_name, config_name)
-- 需求 6.1：主键 = 型号名 + 配置名；冷却/结构差异体现在 model_name 中（第 62 轮 P2-5）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS motor (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,

    -- 业务主键：型号名 + 配置名（需求 6.1）
    model_name      VARCHAR(100)    NOT NULL COMMENT '型号名，如 ADR110-B136、DDR-080-气冷',
    config_name     VARCHAR(20)     NOT NULL COMMENT '配置名（电气接法）：默认/串联/并联',

    -- 类型决定使用哪套规格表（需求 6.2 按类型分叉）
    motor_type      VARCHAR(10)     NOT NULL COMMENT '电机类型：linear=直线 / torque=力矩 / coil=音圈',

    -- 型号属性（需求 6.1：厂商/系列/结构/冷却/中孔均为行上的属性）
    structure       VARCHAR(20)     NOT NULL COMMENT '结构类型：无铁芯/有铁芯/超声波力矩电机/音圈',
    cooling         VARCHAR(20)     NOT NULL COMMENT '冷却方式：普通/水冷/气冷',
    bore            VARCHAR(20)              COMMENT '中孔规格：标准/特殊；仅力矩电机有值，其余为 NULL',

    -- 来源信息（需求 6.3）
    manufacturer    VARCHAR(50)              COMMENT '厂商',
    series          VARCHAR(50)              COMMENT '系列',
    source_doc      VARCHAR(200)             COMMENT '样册文件名',
    source_page     VARCHAR(20)              COMMENT '样册页码',
    entry_method    VARCHAR(20)     NOT NULL COMMENT '录入方式：手工录入/识别草稿',
    entered_on      DATE                     COMMENT '录入日期（UTC 日期）',
    note            VARCHAR(1000)            COMMENT '样册选型说明（人类可读，不参与计算与筛选）',

    -- 审计字段（规则 R2-5 / R3-5：UTC 存储）
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间（UTC），由应用层写入',

    -- 业务唯一约束（规则 R4-4）：同一型号名 + 配置名只能有一条
    CONSTRAINT uk_motor_model_config UNIQUE (model_name, config_name),

    -- 状态字段取值集（规则 R3-6）
    CONSTRAINT ck_motor_type     CHECK (motor_type IN ('linear', 'torque', 'coil')),
    CONSTRAINT ck_motor_config   CHECK (config_name IN ('默认', '串联', '并联')),
    CONSTRAINT ck_motor_structure CHECK (structure IN ('无铁芯', '有铁芯', '超声波力矩电机', '音圈')),
    CONSTRAINT ck_motor_cooling  CHECK (cooling IN ('普通', '水冷', '气冷')),
    CONSTRAINT ck_motor_bore     CHECK (bore IS NULL OR bore IN ('标准', '特殊')),
    CONSTRAINT ck_motor_entry    CHECK (entry_method IN ('手工录入', '识别草稿'))
);

-- 索引：候选池按页签（类型）取数，再按结构/冷却筛选（需求 7.3）
CREATE INDEX IF NOT EXISTS idx_motor_type_structure_cooling
    ON motor (motor_type, structure, cooling);


-- ----------------------------------------------------------------------------
-- 表 2：motor_linear_spec —— 直线电机规格（与 motor 1:1）
-- 字段模板见 doc/design/01 第 6 章 / prototype/js/data.js: motorFieldGroups.linear
-- 必填 9 项 NOT NULL（规则 R3-2）：需求 6.2 第 1 档 + 第 49 轮电流必填；最高线圈温度第 67 轮转选填
-- 单位：全部为内部计算单位（K2 factor 已在应用层完成换算，第 63 轮裁定）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS motor_linear_spec (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    motor_id            BIGINT          NOT NULL COMMENT '逻辑关联 motor.id（规则 R4-2：不建物理外键）',

    -- ===== 性能参数 / 必填 9 项 + 选填 1 项 =====
    force_cont          DECIMAL(18,6)   NOT NULL COMMENT '持续推力 (N)',
    force_peak          DECIMAL(18,6)   NOT NULL COMMENT '峰值推力 (N)',
    kf                  DECIMAL(18,6)   NOT NULL COMMENT '力常数 (N/Arms)',
    ke                  DECIMAL(18,6)   NOT NULL COMMENT '反电势常数 (Vpeak/(m/s))',
    r25                 DECIMAL(18,6)   NOT NULL COMMENT '相间电阻 @25°C (Ω)',
    i_cont              DECIMAL(18,6)   NOT NULL COMMENT '持续电流 (Arms)',
    i_peak              DECIMAL(18,6)   NOT NULL COMMENT '峰值电流 (Arms)',
    t_max               DECIMAL(18,6)            COMMENT '最高线圈温度 (°C)；选填，留空时不做热校核（第 67 轮）',
    kth                 DECIMAL(18,6)   NOT NULL COMMENT '热耗散常数 (W/°C)',
    u_bus               DECIMAL(18,6)   NOT NULL COMMENT '最高母线电压 (Vdc)',

    -- ===== 性能参数 / 选填 =====
    km                  DECIMAL(18,6)            COMMENT '电机常数 @25°C (N/SqRt(W))；留空时由详情表派生',
    l                   DECIMAL(18,6)            COMMENT '相间电感 (mH)；不参与温度与电压计算',
    te                  DECIMAL(18,6)            COMMENT '电气时间常数 (ms)',
    pn                  DECIMAL(18,6)            COMMENT '持续热功率 (W)',
    pole_pitch          DECIMAL(18,6)            COMMENT '电磁周期 (mm)',
    mag_force           DECIMAL(18,6)            COMMENT '磁吸力 (kN)',

    -- ===== 机械参数 / 选填 =====
    mover_mass          DECIMAL(18,6)            COMMENT '线圈质量 (kg)',
    coil_length         DECIMAL(18,6)            COMMENT '线圈长度 (mm)',
    track_mass          DECIMAL(18,6)            COMMENT '磁轨质量（每米）(kg/m)',

    -- ===== 准入补充 / 选填（样册通常不提供；留空 = 跳过准入②，不阻断推荐，第 43/44 轮）=====
    max_speed_peak      DECIMAL(18,6)            COMMENT '最高速度 @峰值推力 (m/s)',
    max_speed_cont      DECIMAL(18,6)            COMMENT '最高速度 @持续推力 (m/s)',

    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间（UTC）',

    -- 1:1 约束：一条主表记录最多一条直线规格
    CONSTRAINT uk_linear_spec_motor UNIQUE (motor_id)
);

CREATE INDEX IF NOT EXISTS idx_linear_spec_motor ON motor_linear_spec (motor_id);


-- ----------------------------------------------------------------------------
-- 表 3：motor_torque_spec —— 力矩（旋转）电机规格（与 motor 1:1）
-- 必填 9 项 NOT NULL；双转速阈值可空（第 66 轮：样册口径不统一，缺项只跳过准入②）；
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS motor_torque_spec (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    motor_id            BIGINT          NOT NULL COMMENT '逻辑关联 motor.id（规则 R4-2：不建物理外键）',

    -- ===== 性能参数 / 必填 9 项 + 选填 1 项 =====
    torque_cont         DECIMAL(18,6)   NOT NULL COMMENT '持续转矩 @100°C (Nm)',
    torque_peak         DECIMAL(18,6)   NOT NULL COMMENT '峰值转矩 (Nm)',
    kt                  DECIMAL(18,6)   NOT NULL COMMENT '转矩常数 (Nm/Arms)',
    ke                  DECIMAL(18,6)   NOT NULL COMMENT '反电势常数 (Vpeak/rpm)',
    r25                 DECIMAL(18,6)   NOT NULL COMMENT '相间电阻 @25°C (Ω)',
    i_cont              DECIMAL(18,6)   NOT NULL COMMENT '持续电流 (Arms)',
    i_peak              DECIMAL(18,6)   NOT NULL COMMENT '峰值电流 (Arms)',
    t_max               DECIMAL(18,6)            COMMENT '最高线圈温度 (°C)；选填，留空时不做热校核（第 67 轮）',
    kth                 DECIMAL(18,6)   NOT NULL COMMENT '热耗散常数 (W/°C)',
    u_bus               DECIMAL(18,6)   NOT NULL COMMENT '最高母线电压 (Vdc)',
    max_speed_peak      DECIMAL(18,6)            COMMENT '最高转速 @峰值转矩 (rpm)；留空只跳过准入②（第 66 轮）',
    max_speed_cont      DECIMAL(18,6)            COMMENT '最高转速 @持续转矩 (rpm)；留空只跳过准入②（第 66 轮）',

    -- ===== 性能参数 / 选填 =====
    km                  DECIMAL(18,6)            COMMENT '电机常数 @25°C (Nm/SqRt(W))；留空时由详情表派生',
    l                   DECIMAL(18,6)            COMMENT '相间电感 (mH)',
    te                  DECIMAL(18,6)            COMMENT '电气时间常数 (ms)',
    pn                  DECIMAL(18,6)            COMMENT '持续热功率 (W)',
    poles               DECIMAL(18,6)            COMMENT '极数',

    -- ===== 机械参数 / 选填 =====
    total_mass          DECIMAL(18,6)            COMMENT '总质量 (kg)',
    mover_mass          DECIMAL(18,6)            COMMENT '线圈质量 (kg)；ACR 弧形电机等样册提供（第 68 轮）',
    inertia             DECIMAL(18,6)            COMMENT '转动惯量 (kg·m²)；仅作参数展示，不参与计算（第 45 轮）',
    runout_axial        DECIMAL(18,6)            COMMENT '轴向端跳 (μm)',
    runout_radial       DECIMAL(18,6)            COMMENT '径向端跳 (μm)',
    load_axial_normal   DECIMAL(18,6)            COMMENT '最大轴向载荷（正常安装）(N)',
    load_axial_inverted DECIMAL(18,6)            COMMENT '最大轴向载荷（倒装/侧装）(N)',
    load_torque_normal  DECIMAL(18,6)            COMMENT '最大扭矩载荷（正常安装）(Nm)',
    load_torque_inverted DECIMAL(18,6)           COMMENT '最大扭矩载荷（倒装/侧装）(Nm)',

    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间（UTC）',

    CONSTRAINT uk_torque_spec_motor UNIQUE (motor_id)
);

CREATE INDEX IF NOT EXISTS idx_torque_spec_motor ON motor_torque_spec (motor_id);


-- ----------------------------------------------------------------------------
-- 表 4：motor_coil_spec —— 音圈电机规格（与 motor 1:1）
-- 必填 9 项与直线一致（第 48/49 轮删一法实测结论），但语义为单相：
--   kf 单位 N/A（非 N/Arms）、r25 为单相电阻（非相间电阻）、i_cont/i_peak 为 A（非 Arms）
--   故不能与直线规格合并存储（避免同名列承载不同物理意义）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS motor_coil_spec (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    motor_id            BIGINT          NOT NULL COMMENT '逻辑关联 motor.id（规则 R4-2：不建物理外键）',

    -- ===== 性能参数 / 必填 9 项 + 选填 1 项（单相语义）=====
    force_cont          DECIMAL(18,6)   NOT NULL COMMENT '持续推力 @100°C (N)',
    force_peak          DECIMAL(18,6)   NOT NULL COMMENT '峰值推力 (N)',
    kf                  DECIMAL(18,6)   NOT NULL COMMENT '力常数 (N/A，单相)',
    ke                  DECIMAL(18,6)   NOT NULL COMMENT '反电势常数 (V/(m/s))',
    r25                 DECIMAL(18,6)   NOT NULL COMMENT '电阻 @25°C (Ω，单相非相间)',
    i_cont              DECIMAL(18,6)   NOT NULL COMMENT '持续电流 (A，单相)',
    i_peak              DECIMAL(18,6)   NOT NULL COMMENT '峰值电流 (A，单相)',
    t_max               DECIMAL(18,6)            COMMENT '最高线圈温度 (°C)；选填，留空时不做热校核（第 67 轮）',
    kth                 DECIMAL(18,6)   NOT NULL COMMENT '热耗散常数 (W/°C)',
    u_bus               DECIMAL(18,6)   NOT NULL COMMENT '最高电压 (Vdc)',

    -- ===== 性能参数 / 选填 =====
    stroke              DECIMAL(18,6)            COMMENT '行程 (mm)；仅作参数展示，不参与准入判定（第 38 轮）',
    km                  DECIMAL(18,6)            COMMENT '电机常数 @25°C (N/SqRt(W))；留空时由详情表派生（单相为 Kf/√R）',
    l                   DECIMAL(18,6)            COMMENT '电感 (mH)',
    te                  DECIMAL(18,6)            COMMENT '电气时间常数 (ms)',
    pn                  DECIMAL(18,6)            COMMENT '持续热功率 (W)',

    -- ===== 机械参数 / 选填 =====
    mover_mass          DECIMAL(18,6)            COMMENT '线圈质量 (kg)；录入单位为 g，由 K2 factor 换算后入库',
    core_mass           DECIMAL(18,6)            COMMENT '铁芯质量 (kg)；录入单位为 g，需 K2 补充换算系数（见文档待确认项）',
    gap                 DECIMAL(18,6)            COMMENT '运动间隙 (mm)',

    -- ===== 准入补充 / 选填 =====
    max_speed_peak      DECIMAL(18,6)            COMMENT '最高速度 @峰值推力 (m/s)',
    max_speed_cont      DECIMAL(18,6)            COMMENT '最高速度 @持续推力 (m/s)',

    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间（UTC）',

    CONSTRAINT uk_coil_spec_motor UNIQUE (motor_id)
);

CREATE INDEX IF NOT EXISTS idx_coil_spec_motor ON motor_coil_spec (motor_id);


-- ============================================================================
-- 结构迁移（幂等，每次启动执行）
--
-- 第 66 轮：力矩双转速阈值改为选填。CREATE TABLE IF NOT EXISTS 不会改动已建好的表，
-- 故对存量库补一条 ALTER；H2 对已可空的列重复执行 SET NULL 是空操作，可安全重复跑。
-- ============================================================================
ALTER TABLE motor_torque_spec ALTER COLUMN max_speed_peak SET NULL;
ALTER TABLE motor_torque_spec ALTER COLUMN max_speed_cont SET NULL;

-- 第 67 轮：最高线圈温度改为选填（三套模板同步）。缺失时热校核不判定，
-- 准入不再因缺它而 UNKNOWN；存量库需放开该列，否则留空保存会被 NOT NULL 拒绝。
ALTER TABLE motor_linear_spec ALTER COLUMN t_max SET NULL;
ALTER TABLE motor_torque_spec ALTER COLUMN t_max SET NULL;
ALTER TABLE motor_coil_spec ALTER COLUMN t_max SET NULL;

-- 第 68 轮：力矩电机增加「线圈质量」（ACR 弧形电机 / 圆弧型直线电机的样册给这一项）。
-- CREATE TABLE IF NOT EXISTS 不会改动已建好的表，故对存量库补一条 ADD COLUMN；
-- H2 的 IF NOT EXISTS 让这条可安全重复执行。列为可空，存量数据不受影响。
ALTER TABLE motor_torque_spec ADD COLUMN IF NOT EXISTS mover_mass DECIMAL(18,6);


-- ============================================================================
-- 说明（非 SQL，供后续阶段参考）
--
-- 1. 数值精度：统一 DECIMAL(18,6)。已知损失——极小的参数（如转动惯量
--    4.419e-4）会截断到 0.000442，相对误差约 0.023%。该值仅作参数展示、
--    不参与任何计算（第 45 轮已不再叠加电机惯量），故影响可忽略。
--
-- 2. 类型一致性：motor.motor_type 与所关联规格表的对应关系由应用层（S1/S3）
--    保证——linear → motor_linear_spec、coil → motor_coil_spec、
--    torque → motor_torque_spec。数据库层无物理外键，无法强制该约束（规则 R4-2）。
--
-- 3. 删除策略：v1 不提供删除功能（第 63 轮裁定）。若后续新增删除，
--    须在同一事务中删除 motor 及其规格表记录，避免孤儿行。
--
-- 4. 时间字段：全部按 UTC 存储，展示层按本地时区转换（规则 R3-5）。
--
-- 5. updated_at 由应用层在更新时写入；H2 不提供 MySQL 的 ON UPDATE 语法。
-- ============================================================================
