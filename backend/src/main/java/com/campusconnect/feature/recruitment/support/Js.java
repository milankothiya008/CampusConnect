package com.campusconnect.feature.recruitment.support;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The JavaScript coercions the original services applied to raw JSON values ({@code String(x)}, {@code Number(x)},
 * truthiness, {@code trim()}). Drive forms and answers are free-form JSON, so the same input must normalise to the
 * same result and the same messages as before.
 */
public final class Js {

    private static final Pattern DECIMAL = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");
    private static final Pattern HEX = Pattern.compile("^0[xX][0-9a-fA-F]+$");
    /** validator.js isInt (leading zeroes allowed). */
    private static final Pattern INT = Pattern.compile("^[-+]?[0-9]+$");

    private Js() {
    }

    /** {@code String(value ?? "")}. */
    public static String string(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number)) {
                return "NaN";
            }
            if (Double.isInfinite(number)) {
                return number > 0 ? "Infinity" : "-Infinity";
            }
            return number == Math.rint(number) && Math.abs(number) < 1e21 ? String.valueOf((long) number) : String.valueOf(number);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item == null ? "" : string(item)).collect(Collectors.joining(","));
        }
        if (value instanceof Map<?, ?>) {
            return "[object Object]";
        }
        return String.valueOf(value);
    }

    /** {@code String.prototype.trim()}: JavaScript white space and line terminators, including no-break spaces. */
    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0xA0 || c == 0xFEFF || c == 0x2028 || c == 0x2029
                || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /** {@code String(value ?? "").trim().slice(0, max)} (cleanText). */
    public static String clean(Object value, int max) {
        String text = trim(string(value));
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** JavaScript truthiness of a JSON value. */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        return true;
    }

    /** {@code Number(value)}; NaN when the value is not numeric. */
    public static double number(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof String text) {
            String trimmed = trim(text);
            if (trimmed.isEmpty()) {
                return 0;
            }
            if (DECIMAL.matcher(trimmed).matches()) {
                return Double.parseDouble(trimmed);
            }
            if (HEX.matcher(trimmed).matches()) {
                return Long.parseLong(trimmed.substring(2), 16);
            }
            return switch (trimmed) {
                case "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> Double.NaN;
            };
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? 0 : list.size() == 1 ? number(string(list.get(0))) : Double.NaN;
        }
        return Double.NaN;
    }

    /** {@code Number.isInteger(value)}. */
    public static boolean isInteger(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.floor(value);
    }

    /** express-validator's {@code isInt({ min, max })}, which checks the value's string form. */
    public static boolean isInt(Object value, long min, long max) {
        String text = validatorString(value);
        if (!INT.matcher(text).matches()) {
            return false;
        }
        double number = Double.parseDouble(text);
        return number >= min && number <= max;
    }

    /** The string express-validator's standard validators see: '' for null and undefined, else String(value). */
    public static String validatorString(Object value) {
        return value == null ? "" : string(value);
    }
}
