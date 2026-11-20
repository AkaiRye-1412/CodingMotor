package com.motorsizer.app.service;

import com.motorsizer.app.entity.MotorCoilSpecEntity;
import com.motorsizer.app.entity.MotorLinearSpecEntity;
import com.motorsizer.app.entity.MotorTorqueSpecEntity;
import com.motorsizer.domain.catalog.MotorType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * spec（Map，已校验为内部单位数值）与三张规格表实体之间的映射。
 *
 * <p>字段顺序按 K2 模板组织，保证 C2 响应稳定可 diff。
 */
public final class MotorSpecConverter {

    private MotorSpecConverter() {
    }

    public static MotorLinearSpecEntity toLinearEntity(
            Long motorId, Map<String, BigDecimal> spec, LocalDateTime now) {
        MotorLinearSpecEntity e = new MotorLinearSpecEntity();
        e.setMotorId(motorId);
        e.setForceCont(v(spec, "forceCont"));
        e.setForcePeak(v(spec, "forcePeak"));
        e.setKf(v(spec, "kf"));
        e.setKe(v(spec, "ke"));
        e.setR25(v(spec, "r25"));
        e.setICont(v(spec, "iCont"));
        e.setIPeak(v(spec, "iPeak"));
        e.setTMax(v(spec, "tMax"));
        e.setKth(v(spec, "kth"));
        e.setUBus(v(spec, "uBus"));
        e.setKm(v(spec, "km"));
        e.setL(v(spec, "l"));
        e.setTe(v(spec, "te"));
        e.setPn(v(spec, "pn"));
        e.setPolePitch(v(spec, "polePitch"));
        e.setMagForce(v(spec, "magForce"));
        e.setMoverMass(v(spec, "moverMass"));
        e.setCoilLength(v(spec, "coilLength"));
        e.setTrackMass(v(spec, "trackMass"));
        e.setMaxSpeedPeak(v(spec, "maxSpeedPeak"));
        e.setMaxSpeedCont(v(spec, "maxSpeedCont"));
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    public static MotorTorqueSpecEntity toTorqueEntity(
            Long motorId, Map<String, BigDecimal> spec, LocalDateTime now) {
        MotorTorqueSpecEntity e = new MotorTorqueSpecEntity();
        e.setMotorId(motorId);
        e.setTorqueCont(v(spec, "torqueCont"));
        e.setTorquePeak(v(spec, "torquePeak"));
        e.setKt(v(spec, "kt"));
        e.setKe(v(spec, "ke"));
        e.setR25(v(spec, "r25"));
        e.setICont(v(spec, "iCont"));
        e.setIPeak(v(spec, "iPeak"));
        e.setTMax(v(spec, "tMax"));
        e.setKth(v(spec, "kth"));
        e.setUBus(v(spec, "uBus"));
        e.setMaxSpeedPeak(v(spec, "maxSpeedPeak"));
        e.setMaxSpeedCont(v(spec, "maxSpeedCont"));
        e.setKm(v(spec, "km"));
        e.setL(v(spec, "l"));
        e.setTe(v(spec, "te"));
        e.setPn(v(spec, "pn"));
        e.setPoles(v(spec, "poles"));
        e.setTotalMass(v(spec, "totalMass"));
        e.setMoverMass(v(spec, "moverMass"));
        e.setInertia(v(spec, "inertia"));
        e.setRunoutAxial(v(spec, "runoutAxial"));
        e.setRunoutRadial(v(spec, "runoutRadial"));
        e.setLoadAxialNormal(v(spec, "loadAxialNormal"));
        e.setLoadAxialInverted(v(spec, "loadAxialInverted"));
        e.setLoadTorqueNormal(v(spec, "loadTorqueNormal"));
        e.setLoadTorqueInverted(v(spec, "loadTorqueInverted"));
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    public static MotorCoilSpecEntity toCoilEntity(
            Long motorId, Map<String, BigDecimal> spec, LocalDateTime now) {
        MotorCoilSpecEntity e = new MotorCoilSpecEntity();
        e.setMotorId(motorId);
        e.setForceCont(v(spec, "forceCont"));
        e.setForcePeak(v(spec, "forcePeak"));
        e.setKf(v(spec, "kf"));
        e.setKe(v(spec, "ke"));
        e.setR25(v(spec, "r25"));
        e.setICont(v(spec, "iCont"));
        e.setIPeak(v(spec, "iPeak"));
        e.setTMax(v(spec, "tMax"));
        e.setKth(v(spec, "kth"));
        e.setUBus(v(spec, "uBus"));
        e.setStroke(v(spec, "stroke"));
        e.setKm(v(spec, "km"));
        e.setL(v(spec, "l"));
        e.setTe(v(spec, "te"));
        e.setPn(v(spec, "pn"));
        e.setMoverMass(v(spec, "moverMass"));
        e.setCoreMass(v(spec, "coreMass"));
        e.setGap(v(spec, "gap"));
        e.setMaxSpeedPeak(v(spec, "maxSpeedPeak"));
        e.setMaxSpeedCont(v(spec, "maxSpeedCont"));
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    /** 反向：实体 → spec Map（不含 derived）。 */
    public static Map<String, Object> toSpecMap(MotorType type, Object entity) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (type == MotorType.LINEAR && entity instanceof MotorLinearSpecEntity e) {
            put(spec, "forceCont", e.getForceCont());
            put(spec, "forcePeak", e.getForcePeak());
            put(spec, "kf", e.getKf());
            put(spec, "ke", e.getKe());
            put(spec, "km", e.getKm());
            put(spec, "r25", e.getR25());
            put(spec, "l", e.getL());
            put(spec, "te", e.getTe());
            put(spec, "iCont", e.getICont());
            put(spec, "iPeak", e.getIPeak());
            put(spec, "pn", e.getPn());
            put(spec, "tMax", e.getTMax());
            put(spec, "kth", e.getKth());
            put(spec, "uBus", e.getUBus());
            put(spec, "polePitch", e.getPolePitch());
            put(spec, "magForce", e.getMagForce());
            put(spec, "moverMass", e.getMoverMass());
            put(spec, "coilLength", e.getCoilLength());
            put(spec, "trackMass", e.getTrackMass());
            put(spec, "maxSpeedPeak", e.getMaxSpeedPeak());
            put(spec, "maxSpeedCont", e.getMaxSpeedCont());
        } else if (type == MotorType.TORQUE && entity instanceof MotorTorqueSpecEntity e) {
            put(spec, "torqueCont", e.getTorqueCont());
            put(spec, "torquePeak", e.getTorquePeak());
            put(spec, "kt", e.getKt());
            put(spec, "ke", e.getKe());
            put(spec, "km", e.getKm());
            put(spec, "r25", e.getR25());
            put(spec, "l", e.getL());
            put(spec, "te", e.getTe());
            put(spec, "iCont", e.getICont());
            put(spec, "iPeak", e.getIPeak());
            put(spec, "pn", e.getPn());
            put(spec, "tMax", e.getTMax());
            put(spec, "kth", e.getKth());
            put(spec, "uBus", e.getUBus());
            put(spec, "poles", e.getPoles());
            put(spec, "maxSpeedPeak", e.getMaxSpeedPeak());
            put(spec, "maxSpeedCont", e.getMaxSpeedCont());
            put(spec, "totalMass", e.getTotalMass());
            put(spec, "moverMass", e.getMoverMass());
            put(spec, "inertia", e.getInertia());
            put(spec, "runoutAxial", e.getRunoutAxial());
            put(spec, "runoutRadial", e.getRunoutRadial());
            put(spec, "loadAxialNormal", e.getLoadAxialNormal());
            put(spec, "loadAxialInverted", e.getLoadAxialInverted());
            put(spec, "loadTorqueNormal", e.getLoadTorqueNormal());
            put(spec, "loadTorqueInverted", e.getLoadTorqueInverted());
        } else if (type == MotorType.COIL && entity instanceof MotorCoilSpecEntity e) {
            put(spec, "stroke", e.getStroke());
            put(spec, "forceCont", e.getForceCont());
            put(spec, "forcePeak", e.getForcePeak());
            put(spec, "kf", e.getKf());
            put(spec, "ke", e.getKe());
            put(spec, "km", e.getKm());
            put(spec, "r25", e.getR25());
            put(spec, "l", e.getL());
            put(spec, "te", e.getTe());
            put(spec, "iCont", e.getICont());
            put(spec, "iPeak", e.getIPeak());
            put(spec, "pn", e.getPn());
            put(spec, "tMax", e.getTMax());
            put(spec, "kth", e.getKth());
            put(spec, "uBus", e.getUBus());
            put(spec, "moverMass", e.getMoverMass());
            put(spec, "coreMass", e.getCoreMass());
            put(spec, "gap", e.getGap());
            put(spec, "maxSpeedPeak", e.getMaxSpeedPeak());
            put(spec, "maxSpeedCont", e.getMaxSpeedCont());
        }
        return spec;
    }

    private static BigDecimal v(Map<String, BigDecimal> spec, String key) {
        return spec.get(key);
    }

    private static void put(Map<String, Object> target, String key, BigDecimal value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
