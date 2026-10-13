package com.campusconnect.feature.club;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The JavaScript value rules the Node code relied on — truthiness, {@code String(value)}, express-validator's
 * {@code toString}, {@code String.prototype.trim} and Mongoose's Boolean cast — so the ported checks treat unusual
 * request values (numbers, arrays, "false" strings) exactly as before.
 */
public final class JsValues {

    /** JavaScript's {@code \s} (Java's {@code \s} lacks the Unicode spaces). */
    private static final String SPACE = "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
    private static final Pattern EDGES = Pattern.compile("^" + SPACE + "+|" + SPACE + "+$");
    private static final Pattern RUNS = Pattern.compile(SPACE + "+");
    private static final Set<String> MONGOOSE_TRUE = Set.of("true", "1", "yes");

    private JsValues() {
    }

    /** {@code Boolean(value)}. */
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
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        return true;
    }

    /** {@code String(value)}. */
    public static String string(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item == null ? "" : string(item)).collect(Collectors.joining(","));
        }
        if (value instanceof Map<?, ?>) {
            return "[object Object]";
        }
        if (value instanceof Number number) {
            return number(number);
        }
        return String.valueOf(value);
    }

    /** express-validator's {@code toString}: null becomes "" (validators and sanitizers see this). */
    public static String stringify(Object value) {
        if (value == null || value instanceof Number number && Double.isNaN(number.doubleValue())) {
            return "";
        }
        return string(value);
    }

    /** {@code String.prototype.trim}. */
    public static String trim(String value) {
        return value == null ? null : EDGES.matcher(value).replaceAll("");
    }

    /** {@code text.replace(/\s+/g, " ")}. */
    public static String collapseSpaces(String value) {
        return RUNS.matcher(value).replaceAll(" ");
    }

    /** {@code String(value ?? "").trim()}. */
    public static String trimmed(Object value) {
        return trim(value == null ? "" : string(value));
    }

    /** Mongoose's Boolean cast of a value that passed {@code isBoolean()}: true, "true", 1, "1" and "yes" are true. */
    public static boolean mongooseBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() == 1;
        }
        return value instanceof String text && MONGOOSE_TRUE.contains(text);
    }

    static String number(Number number) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte || number instanceof BigInteger) {
            return number.toString();
        }
        if (number instanceof BigDecimal decimal) {
            BigDecimal plain = decimal.stripTrailingZeros();
            return plain.scale() <= 0 ? plain.toBigInteger().toString() : plain.toPlainString();
        }
        double d = number.doubleValue();
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            return BigDecimal.valueOf(d).toBigInteger().toString();
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
