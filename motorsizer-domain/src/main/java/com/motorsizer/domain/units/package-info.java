/**
 * K3：单位与量纲桥接。
 *
 * <p>职责：录入单位 ↔ 内部计算单位的换算常量与工具（直线 m/s·N、旋转 rpm·Nm·°、音圈 g→kg）。
 * 单位换算系数只允许在此定义，Controller / Service 不得各自硬编码。
 *
 * <p>边界：不依赖 Spring / MyBatis / Jackson；不承载业务判定。
 */
package com.motorsizer.domain.units;
