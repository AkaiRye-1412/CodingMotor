/**
 * K1：运动规划（motion）。
 *
 * <p>职责：点到点梯形/三角形规划、自定义模式反解、S 曲线上抬（τ）与阶段切分。
 * 输入输出使用不可变值对象（见 {@code com.motorsizer.domain.model}）。
 *
 * <p>边界：纯 Java、无框架依赖；规划失败以领域结果对象返回，不抛出 Web 异常。
 */
package com.motorsizer.domain.motion;
