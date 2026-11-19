package com.motorsizer.app.dto;

import java.util.List;
import java.util.Map;

/** B1 响应体（04 §3 B1）。 */
public record CalculationResponse(
        Stage stage,
        Units units,
        Curve curve,
        Theoretical theoretical,
        List<Candidate> candidates,
        SelectedMotor selectedMotor,
        Sampling sampling) {

    public record Stage(boolean ok, String code, String message, Map<String, Object> details) {

        public static Stage success() {
            return new Stage(true, "OK", null, null);
        }

        public static Stage failure(String code, String message, Map<String, Object> details) {
            return new Stage(false, code, message, details);
        }
    }

    public record Units(
            String velocity,
            String acceleration,
            String force,
            String distance,
            String time,
            String temperature,
            String voltage) {
    }

    public record Curve(
            String mode,
            String shape,
            boolean smoothApplied,
            double tauMs,
            double time,
            double peakVelocity,
            double peakAcceleration,
            Double peakAccelerationBeforeSmooth,
            Double accelLiftPercent,
            double tAccel,
            double tCruise,
            double tDecel,
            boolean timeDerived,
            boolean shapeAutoDetected,
            String note) {
    }

    public record Theoretical(
            double contForce,
            double peakForce,
            double cycleTime,
            double gravityTerm) {
    }

    public record Candidate(
            Long motorId,
            String modelName,
            String configName,
            String motorType,
            String structure,
            String cooling,
            double headroomRatio,
            boolean overSized,
            List<String> reasons,
            List<String> speedCheckSkipped) {
    }

    public record MotorParameter(
            String key,
            String label,
            String unit,
            Double value,
            boolean derived) {
    }

    public record SelectedMotor(
            Long motorId,
            boolean inRecommendation,
            String outOfRangeReason,
            List<MotorParameter> parameters,
            Applied applied) {
    }

    public record Applied(
            Double coilTemp,
            Double contCurrent,
            Double peakCurrent,
            Double busVoltage,
            Double safetyPercent,
            Double contLoadPercent,
            Double peakLoadPercent,
            String thermalVerdict,
            Boolean overBusVoltage,
            Supply supply,
            List<String> missing,
            /** 热校核缺的电机参数（相间电阻 / 热耗散常数 / 力常数 / 最高线圈温度），空表示齐全。 */
            List<String> thermalMissing) {
    }

    public record Supply(
            double have,
            double need,
            double remain,
            boolean pass,
            String text) {
    }

    public record Sampling(
            List<SpeedPoint> speed,
            List<ForcePoint> force,
            int pointCount) {
    }

    public record SpeedPoint(double t, double v) {
    }

    public record ForcePoint(double t, double f) {
    }
}
