package com.campusconnect.feature.hackathon;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The JavaScript conversions the Node hackathon code applies to its loosely typed request bodies ({@code String(x)},
 * {@code Number(x)}, truthiness, {@code new Date(x)}, {@code trim()}), so the same inputs are accepted and refused
 * with the same messages. JSON values arrive as Jackson's plain types: String, Integer/Long/Double, Boolean,
 * {@code Map}, {@code List} or null.
 */
final class JsValues {

    /** A property that was not sent at all — JavaScript's {@code undefined}, as opposed to {@code null}. */
    static final Object UNDEFINED = new Object();

    private static final Pattern DECIMAL = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");
    private static final Pattern HEX = Pattern.compile("^0[xX][0-9a-fA-F]+$");
    private static final Pattern OCTAL = Pattern.compile("^0[oO][0-7]+$");
    private static final Pattern BINARY = Pattern.compile("^0[bB][01]+$");
    private static final Pattern DATE_TIME_WITH_SPACE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}.*)$");
    private static final double MAX_TIME = 8.64e15;

    private JsValues() {
    }

    /** {@code object?.[key]}: the value, null when sent as null, {@link #UNDEFINED} when missing or not an object. */
    static Object get(Object object, String key) {
        if (object instanceof Map<?, ?> map && map.containsKey(key)) {
            return map.get(key);
        }
        return UNDEFINED;
    }

    static boolean truthy(Object value) {
        if (value == null || value == UNDEFINED) {
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

    /** {@code String(value)}. */
    static String string(Object value) {
        if (value == UNDEFINED) {
            return "undefined";
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            return numberToString(((Number) value).doubleValue());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item == null || item == UNDEFINED ? "" : string(item)).collect(Collectors.joining(","));
        }
        if (value instanceof Map<?, ?>) {
            return "[object Object]";
        }
        return String.valueOf(value);
    }

    /** {@code String(value || "")}. */
    static String text(Object value) {
        return truthy(value) ? string(value) : "";
    }

    /** {@code String(value ?? "")}. */
    static String textOrEmpty(Object value) {
        return value == null || value == UNDEFINED ? "" : string(value);
    }

    /** {@code String.prototype.trim()}: JavaScript white space and line terminators (no-break spaces included). */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJsSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isJsSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isJsSpace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0x00A0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    /** {@code value.slice(0, max)}. */
    static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** {@code Number(value)}. */
    static double number(Object value) {
        if (value == UNDEFINED) {
            return Double.NaN;
        }
        if (value == null) {
            return 0;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            return parseNumber(trim(text));
        }
        if (value instanceof List<?>) {
            return parseNumber(trim(string(value)));
        }
        return Double.NaN;
    }

    private static double parseNumber(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        switch (text) {
            case "Infinity", "+Infinity" -> {
                return Double.POSITIVE_INFINITY;
            }
            case "-Infinity" -> {
                return Double.NEGATIVE_INFINITY;
            }
            default -> {
                // fall through to the patterns below
            }
        }
        if (HEX.matcher(text).matches()) {
            return new BigInteger(text.substring(2), 16).doubleValue();
        }
        if (OCTAL.matcher(text).matches()) {
            return new BigInteger(text.substring(2), 8).doubleValue();
        }
        if (BINARY.matcher(text).matches()) {
            return new BigInteger(text.substring(2), 2).doubleValue();
        }
        return DECIMAL.matcher(text).matches() ? Double.parseDouble(text) : Double.NaN;
    }

    /** {@code Number.isInteger(value)}. */
    static boolean isInteger(double value) {
        return Double.isFinite(value) && Math.rint(value) == value;
    }

    /** ECMAScript Number::toString: 31 (not 31.0), 22.333333333333332, 1e+21, 1e-7. */
    static String numberToString(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == 0) {
            return "0";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value < 0) {
            return "-" + numberToString(-value);
        }
        BigDecimal shortest = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        int n = k - shortest.scale();
        if (k <= n && n <= 21) {
            return digits + "0".repeat(n - k);
        }
        if (0 < n && n <= 21) {
            return digits.substring(0, n) + "." + digits.substring(n);
        }
        if (-6 < n && n <= 0) {
            return "0." + "0".repeat(-n) + digits;
        }
        String exponent = (n - 1 >= 0 ? "+" : "-") + Math.abs(n - 1);
        return k == 1 ? digits + "e" + exponent : digits.charAt(0) + "." + digits.substring(1) + "e" + exponent;
    }

    /** {@code new Date(value)}; null when it is an Invalid Date. */
    static Instant date(Object value) {
        if (value == UNDEFINED) {
            return null;
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            double millis = number(value);
            if (!Double.isFinite(millis) || Math.abs(millis) > MAX_TIME) {
                return null;
            }
            return Instant.ofEpochMilli((long) millis);
        }
        return parseDate(trim(string(value)));
    }

    /** The date formats V8 reads: ISO 8601 (date-only = UTC, date-time without offset = local time) and RFC 1123. */
    private static Instant parseDate(String text) {
        String value = DATE_TIME_WITH_SPACE.matcher(text).matches() ? text.replaceFirst(" ", "T") : text;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // not an offset date-time
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // not a local date-time
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // not a date
        }
        try {
            return YearMonth.parse(value).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // not a month
        }
        if (value.matches("^\\d{4}$")) {
            return Year.parse(value).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        try {
            // V8 ignores the weekday, so a wrong one doesn't make the date invalid.
            return ZonedDateTime.parse(value.replaceFirst("^[A-Za-z]{3},\\s*", ""), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
