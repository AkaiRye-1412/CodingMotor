package com.motorsizer.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 音圈电机规格（与 motor 1:1）。
 * <p>单相语义：{@code kf} 为 N/A、{@code r25} 为单相电阻、{@code iCont}/{@code iPeak} 为 A，
 * 不能与直线规格合并存储。
 */
@Data
@TableName("motor_coil_spec")
public class MotorCoilSpecEntity {

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
    private BigDecimal stroke;
    private BigDecimal km;
    private BigDecimal l;
    private BigDecimal te;
    private BigDecimal pn;
    private BigDecimal moverMass;
    private BigDecimal coreMass;
    private BigDecimal gap;
    private BigDecimal maxSpeedPeak;
    private BigDecimal maxSpeedCont;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
