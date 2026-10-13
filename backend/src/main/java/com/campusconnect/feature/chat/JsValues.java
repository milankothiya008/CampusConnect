package com.campusconnect.feature.chat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The JavaScript value rules the original services relied on ({@code value || ""}, {@code String(value)},
 * {@code trim()}, {@code slice(0, n)}, millisecond dates), so loosely typed JSON input reads exactly as before.
 */
public final class JsValues {

    private JsValues() {
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
            double amount = number.doubleValue();
            return amount != 0 && !Double.isNaN(amount);
        }
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        return true;
    }

    /** {@code String(value || "")}. */
    public static String text(Object value) {
        return truthy(value) ? string(value) : "";
    }

    /** {@code String(value)} for JSON scalars (whole numbers print without ".0", as in JavaScript). */
    public static String string(Object value) {
        if ((value instanceof Double || value instanceof Float) && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue())
                && !Double.isInfinite(((Number) value).doubleValue())) {
            return String.valueOf(((Number) value).longValue());
        }
        return String.valueOf(value);
    }

    /** {@code String.prototype.trim()}: JavaScript's white space and line terminators. */
    public static String trim(String value) {
        if (value == null) {
            return "";
        }
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
        return c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r' || c == '﻿' || c == ' ' || c == ' '
                || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /** {@code value.slice(0, max)} (UTF-16 code units, like JavaScript). */
    public static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** {@code String(value || "").trim().slice(0, max)}. */
    public static String clean(Object value, int max) {
        return cut(trim(text(value)), max);
    }

    /** {@code new Date()}: millisecond precision, exactly what MongoDB stores and returns. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** An instant as MongoDB stores it (millisecond precision). */
    public static Instant millis(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
