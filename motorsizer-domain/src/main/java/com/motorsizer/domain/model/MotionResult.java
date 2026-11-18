package com.motorsizer.domain.model;

/**
 * 运动规划结果：成功携带曲线，失败携带计算错误码与可展示文案。
 *
 * <p>失败是正常业务分支（04 §1.1 例外），不是异常；调用方据此在结果区展示原因。
 */
public sealed interface MotionResult {

    record Success(MotionCurve curve) implements MotionResult {
    }

    /**
     * @param maxTau         S 曲线不可行时，当前工况允许的最大平滑时间（ms，保留 1 位小数）
     * @param closestPreset  最接近的可用 τ 预设（{@code null} 表示无可用预设）
     */
    record Failure(CalcErrorCode code, String message, Double maxTau, Integer closestPreset)
            implements MotionResult {

        public static Failure of(CalcErrorCode code, String message) {
            return new Failure(code, message, null, null);
        }
    }
}
