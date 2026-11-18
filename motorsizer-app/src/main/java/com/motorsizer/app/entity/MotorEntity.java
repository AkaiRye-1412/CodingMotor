package com.motorsizer.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 电机主表：公共标识与来源信息。业务主键 = (modelName, configName)。
 */
@Data
@TableName("motor")
public class MotorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelName;
    private String configName;
    private String motorType;
    private String structure;
    private String cooling;
    private String bore;
    private String manufacturer;
    private String series;
    private String sourceDoc;
    private String sourcePage;
    private String entryMethod;
    private LocalDate enteredOn;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
