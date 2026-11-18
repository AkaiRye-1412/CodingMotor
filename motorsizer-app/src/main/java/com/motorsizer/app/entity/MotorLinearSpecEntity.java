package com.motorsizer.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 直线电机规格（与 motor 1:1）。数值均为内部单位。
 */
@Data
@TableName("motor_linear_spec")
public class MotorLinearSpecEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long motorId;

    // 必填 10 项
    private BigDecimal forceCont;
    private BigDecimal forcePeak;
    private BigDecimal kf;
    private BigDecimal ke;
    private BigDecimal r25;
    private BigDecimal iCont;
    private BigDecimal iPeak;
    private BigDecimal tMax;
    private BigDecimal kth;
    private BigDecimal uBus;

    // 选填
    private BigDecimal km;
    private BigDecimal l;
    private BigDecimal te;
    private BigDecimal pn;
    private BigDecimal polePitch;
    private BigDecimal magForce;
    private BigDecimal moverMass;
    private BigDecimal coilLength;
    private BigDecimal trackMass;
    private BigDecimal maxSpeedPeak;
    private BigDecimal maxSpeedCont;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
