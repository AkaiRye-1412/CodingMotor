package com.motorsizer.domain.units;

import java.util.Locale;

/**
 * 显示格式化与取整（K3 的显示口径）。
 *
 * <p>必须逐字符复刻原型 {@code calc.js:fmt/round}，因为计算失败文案中包含格式化后的数值；
 * 黄金快照对文案做完全相等比对。规则：
 * <ol>
 *   <li>非有限值 → {@code "—"}；</li>
 *   <li>{@code |x| < 1e-3} 且非零 → 科学计数法，2 位小数（如 {@code 9.00e-4}）；</li>
 *   <li>其余 → 定点小数（默认 3 位），去掉末尾 0 与孤立小数点。</li>
 * </ol>
 */
public final class ValueFormatter {

    private ValueFormatter() {
    }

    public static String format(Double value) {
        return format(value, null);
    }

    public static String format(double value) {
        return format(Double.valueOf(value), null);
    }

    public static String format(Double value, Integer digits) {
        if (value == null || !Double.isFinite(value)) {
            return "—";
        }

        // 归一化 -0.0：JS 的 -0 不输出负号
        double normalized = value == 0.0 ? 0.0 : value;
        int precision = digits == null ? 3 : digits;

        if (normalized != 0 && Math.abs(normalized) < 1e-3) {
            return toExponential(normalized, 2);
        }

        String text = String.format(Locale.ROOT, "%." + precision + "f", normalized);
        if (text.indexOf('.') >= 0) {
            text = trimTrailingZeros(text);
        }
        return text;
    }

    /** 对应 JS {@code Math.round(x * 10^n) / 10^n}。 */
    public static Double round(Double value, int digits) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        double factor = Math.pow(10, digits);
        return (double) Math.round(value * factor) / factor;
    }

    private static String toExponential(double value, int digits) {
        String text = String.format(Locale.ROOT, "%." + digits + "e", value);
        int exponentIndex = text.indexOf('e');
        String mantissa = text.substring(0, exponentIndex);
        String exponent = text.substring(exponentIndex + 1);

        String sign = "";
        if (exponent.startsWith("-") || exponent.startsWith("+")) {
            sign = exponent.substring(0, 1);
            exponent = exponent.substring(1);
        }

        int firstNonZero = 0;
        while (firstNonZero < exponent.length() - 1 && exponent.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        return mantissa + "e" + sign + exponent.substring(firstNonZero);
    }

    private static String trimTrailingZeros(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    }
}
