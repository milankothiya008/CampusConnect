package com.campusconnect.feature.event.dto;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.feature.event.Values;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * express-validator chains over a JSON body that must stay a map (see {@code EventPayload}): each validator that
 * fails adds its message ("Invalid value" unless {@link Chain#withMessage} names one), standard validators check
 * every item of an array, {@code trim()} writes the trimmed value back into the body, and {@code optional()} skips a
 * missing field — or also null / falsy values. Messages are joined with ". " like middleware/Validate.js.
 */
public final class BodyChecks {

    /** Which values {@code optional()} skips. */
    public enum Skip {
        UNDEFINED,
        NULL,
        FALSY
    }

    private static final String DEFAULT_MESSAGE = "Invalid value";
    private static final Pattern MONGO_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern INT = Pattern.compile("^[-+]?[0-9]+$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern ISO_8601 = Pattern.compile(
            "^[+-]?\\d{4}(-(0[1-9]|1[0-2])(-([12]\\d|0[1-9]|3[01]))?)?"
                    + "([T ]([01]\\d|2[0-3])(:[0-5]\\d(:[0-5]\\d([.,]\\d+)?)?)?([zZ]|[+-]([01]\\d|2[0-3]):?([0-5]\\d)?)?)?$");

    private final Map<String, Object> body;
    private final List<String> errors = new ArrayList<>();

    public BodyChecks(Map<String, Object> body) {
        this.body = body;
    }

    public Chain field(String path) {
        return new Chain(path);
    }

    /** 400 VALIDATION_ERROR with every message, in rule order, when any rule failed. */
    public void throwIfInvalid() {
        if (!errors.isEmpty()) {
            Set<String> distinct = new LinkedHashSet<>(errors);
            throw AppException.badRequest(String.join(". ", distinct), ErrorCode.VALIDATION_ERROR);
        }
    }

    /** express-validator's toString: what standard validators see. */
    static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d)) {
                return "";
            }
            return String.valueOf(Values.jsNumber(d));
        }
        if (value instanceof Map<?, ?>) {
            return "[object Object]";
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(BodyChecks::stringify).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    /** One {@code body(path)} chain. */
    public final class Chain {

        private final String path;
        private boolean skipped;
        private Object value;
        private boolean lastFailed;

        Chain(String path) {
            this.path = path;
            this.value = read(path);
        }

        private boolean present() {
            return exists(path);
        }

        public Chain optional() {
            return optional(Skip.UNDEFINED);
        }

        public Chain optional(Skip skip) {
            skipped = switch (skip) {
                case UNDEFINED -> !present();
                case NULL -> !present() || value == null;
                case FALSY -> !present() || !Values.truthy(value);
            };
            return this;
        }

        public Chain withMessage(String message) {
            if (lastFailed) {
                errors.set(errors.size() - 1, message);
            }
            return this;
        }

        /** A validator on the raw value (express-validator's custom validators: isString, isArray, ...). */
        public Chain custom(Predicate<Object> valid) {
            if (skipped) {
                return this;
            }
            lastFailed = !valid.test(value);
            if (lastFailed) {
                errors.add(DEFAULT_MESSAGE);
            }
            return this;
        }

        /** A validator.js validator, run on each item of an array (on the string form of each value). */
        private Chain standard(Predicate<String> valid) {
            if (skipped) {
                return this;
            }
            lastFailed = false;
            List<?> items = value instanceof Collection<?> list ? new ArrayList<>(list) : java.util.Collections.singletonList(value);
            boolean anyFailed = false;
            for (Object item : items) {
                if (!valid.test(stringify(item))) {
                    anyFailed = true;
                }
            }
            if (anyFailed) {
                errors.add(DEFAULT_MESSAGE);
                lastFailed = true;
            }
            return this;
        }

        public Chain isString() {
            return custom(raw -> raw instanceof String);
        }

        public Chain isArray(Integer min, Integer max) {
            return custom(raw -> raw instanceof Collection<?> list && (min == null || list.size() >= min) && (max == null || list.size() <= max));
        }

        public Chain isObject() {
            return custom(raw -> raw instanceof Map<?, ?>);
        }

        public Chain isLength(int min, int max) {
            return standard(text -> {
                int length = text.codePointCount(0, text.length());
                return length >= min && length <= max;
            });
        }

        public Chain isMongoId() {
            return standard(text -> MONGO_ID.matcher(text).matches());
        }

        public Chain isIn(Collection<String> allowed) {
            return standard(allowed::contains);
        }

        public Chain matches(Pattern pattern) {
            return standard(text -> pattern.matcher(text).find());
        }

        public Chain isBoolean() {
            return standard(text -> text.equals("true") || text.equals("false") || text.equals("1") || text.equals("0"));
        }

        public Chain isInt(int min, int max) {
            return standard(text -> {
                if (!INT.matcher(text).matches()) {
                    return false;
                }
                try {
                    long number = Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
                    return number >= min && number <= max;
                } catch (NumberFormatException e) {
                    return false;
                }
            });
        }

        public Chain isISO8601() {
            return standard(text -> ISO_8601.matcher(text).matches());
        }

        public Chain isEmail() {
            return standard(text -> EMAIL.matcher(text).matches());
        }

        /** Sanitizer: the trimmed string form replaces the value in the body. */
        public Chain trim() {
            if (skipped) {
                return this;
            }
            Object trimmed = value instanceof Collection<?> list
                    ? list.stream().map(item -> stringify(item).strip()).toList()
                    : stringify(value).strip();
            value = trimmed;
            write(path, trimmed);
            return this;
        }
    }

    private boolean exists(String path) {
        String[] parts = path.split("\\.");
        Object current = body;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return false;
            }
            current = map.get(part);
        }
        return true;
    }

    private Object read(String path) {
        Object current = body;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void write(String path, Object value) {
        String[] parts = path.split("\\.");
        Object current = body;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return;
            }
            current = map.get(parts[i]);
        }
        if (current instanceof Map<?, ?> map) {
            try {
                ((Map<String, Object>) map).put(parts[parts.length - 1], value);
            } catch (UnsupportedOperationException e) {
                // An immutable body is only ever validated, never sanitised.
            }
        }
    }

    /** A mutable copy of a JSON object body (or an empty one), so sanitisers can write back. */
    public static Map<String, Object> copy(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    }
}
