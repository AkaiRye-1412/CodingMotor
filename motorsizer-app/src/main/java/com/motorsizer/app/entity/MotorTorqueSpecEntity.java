package com.motorsizer.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 力矩（旋转）电机规格（与 motor 1:1）。含双转速阈值（必填 12 项）。
 */
@Data
@TableName("motor_torque_spec")
public class MotorTorqueSpecEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long motorId;

    // 必填 12 项
    private BigDecimal torqueCont;
    private BigDecimal torquePeak;
    private BigDecimal kt;
    private BigDecimal ke;
    private BigDecimal r25;
    private BigDecimal iCont;
    private BigDecimal iPeak;
    private BigDecimal tMax;
    private BigDecimal kth;
    private BigDecimal uBus;
    private BigDecimal maxSpeedPeak;
    private BigDecimal maxSpeedCont;

    // 选填
    private BigDecimal km;
    private BigDecimal l;
    private BigDecimal te;
    private BigDecimal pn;
    private BigDecimal poles;
    private BigDecimal totalMass;
    /** 线圈质量（ACR 弧形电机等样册提供，第 68 轮）。 */
    private BigDecimal moverMass;
    private BigDecimal inertia;
    private BigDecimal runoutAxial;
    private BigDecimal runoutRadial;
    private BigDecimal loadAxialNormal;
    private BigDecimal loadAxialInverted;
    private BigDecimal loadTorqueNormal;
    private BigDecimal loadTorqueInverted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
