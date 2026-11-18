/**
 * K1：准入判定与排序（screening）。
 *
 * <p>职责：连续/峰值能力两条准入、安全余量（{@code headroomRatio}）、上限软警告、
 * 候选升序排序。准入必填口径与 K2 {@code required} 同源（C-B）。
 *
 * <p>边界：纯 Java；只判定，不访问数据库（候选池由应用层传入）。
 */
package com.motorsizer.domain.screening;
