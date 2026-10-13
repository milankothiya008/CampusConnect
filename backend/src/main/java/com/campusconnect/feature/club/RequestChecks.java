package com.campusconnect.feature.club;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Request validation with express-validator's semantics (validators/RequestValidators.js) for JSON bodies and query
 * strings read as maps. Request records cannot tell a missing field from an explicit null, and the Node rules treat
 * them differently ({@code optional()} skips only missing fields, {@code optional({ values: "null" })} also nulls).
 *
 * <p>Each chain runs its steps in order. Every failing validator adds one message — "Invalid value" unless
 * {@link Chain#withMessage} names it — and standard validators check each element of an array. Sanitizers
 * ({@code trim}, {@code toBoolean}) write the cleaned value back into the map, as express-validator does with
 * {@code req.body}. {@link #validate()} answers 400 with all messages joined by ". ", path parameters first.
 */
public final class RequestChecks {

    private static final String INVALID = "Invalid value";
    private static final Pattern MONGO_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern INT = Pattern.compile("^[-+]?[0-9]+$");
    private static final Pattern ALPHA = Pattern.compile("^[A-Za-z]+$");
    private static final Set<String> BOOLEANS = Set.of("true", "false", "1", "0");
    private static final Pattern EMAIL_PART = Pattern.compile("^[a-z\\d!#$%&'*+\\-/=?^_`{|}~\\u00A1-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TLD = Pattern.compile("^([a-z\\u00A1-\\u00A8\\u00AA-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]{2,}|xn[a-z0-9-]{2,})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL = Pattern.compile("^[a-z\\u00A1-\\uFFFF0-9-]+$", Pattern.CASE_INSENSITIVE);

    private final Map<String, Object> body;
    private final List<String> errors = new ArrayList<>();

    private RequestChecks(Map<String, Object> body) {
        this.body = body;
    }

    /** Checks of a (mutable) JSON body; a missing body counts as {@code {}} like {@code req.body ??= {}}. */
    public static RequestChecks of(Map<String, Object> body) {
        return new RequestChecks(body == null ? new LinkedHashMap<>() : body);
    }

    /** Checks of query parameters; absent ones (null) are left out, so {@code optional()} skips them. */
    public static RequestChecks ofQuery(Map<String, String> parameters) {
        Map<String, Object> present = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            if (value != null) {
                present.put(name, value);
            }
        });
        return new RequestChecks(present);
    }

    /** The checked (and sanitized) values. */
    public Map<String, Object> body() {
        return body;
    }

    /** {@code mongoIdParam(name)}: "Invalid {name}". */
    public RequestChecks param(String name, String value) {
        if (value == null || !MONGO_ID.matcher(value).matches()) {
            errors.add("Invalid " + name);
        }
        return this;
    }

    /** {@code mongoIdBody(name)}: "A valid {name} is required". */
    public RequestChecks mongoId(String field) {
        field(field).isMongoId().withMessage("A valid " + field + " is required");
        return this;
    }

    /** A chain for a field; {@code "list.*"} checks every element of the list (or value of the object). */
    public Chain field(String path) {
        return new Chain(path);
    }

    /** Throws the joined messages, if any check failed. */
    public void validate() {
        if (!errors.isEmpty()) {
            throw AppException.badRequest(String.join(". ", errors), ErrorCode.VALIDATION_ERROR);
        }
    }

    private enum Skip {
        NONE, UNDEFINED, NULL, FALSY
    }

    /** One value a chain checks, and where a sanitized value is written back. */
    private static final class Instance {
        private boolean present;
        private Object value;
        private final Consumer<Object> writer;

        private Instance(boolean present, Object value, Consumer<Object> writer) {
            this.present = present;
            this.value = value;
            this.writer = writer;
        }
    }

    /** {@code body("field")…} — validators and sanitizers in the order they are called. */
    public final class Chain {

        private final List<Instance> instances = new ArrayList<>();
        private final List<Integer> lastErrors = new ArrayList<>();
        private Skip skip = Skip.NONE;

        private Chain(String path) {
            if (path.endsWith(".*")) {
                Object container = body.get(path.substring(0, path.length() - 2));
                if (container instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    List<Object> items = (List<Object>) list;
                    for (int i = 0; i < items.size(); i++) {
                        int index = i;
                        instances.add(new Instance(true, items.get(i), value -> items.set(index, value)));
                    }
                } else if (container instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entries = (Map<String, Object>) map;
                    for (String key : new ArrayList<>(entries.keySet())) {
                        instances.add(new Instance(true, entries.get(key), value -> entries.put(key, value)));
                    }
                }
            } else {
                instances.add(new Instance(body.containsKey(path), body.get(path), value -> body.put(path, value)));
            }
        }

        /** {@code optional()}: skipped when the field is missing. */
        public Chain optional() {
            skip = Skip.UNDEFINED;
            return this;
        }

        /** {@code optional({ values: "null" })}: skipped when missing or null. */
        public Chain optionalNull() {
            skip = Skip.NULL;
            return this;
        }

        /** {@code optional({ values: "falsy" })}: skipped when missing, null, "", 0 or false. */
        public Chain optionalFalsy() {
            skip = Skip.FALSY;
            return this;
        }

        /** Names the error of the validator called just before. */
        public Chain withMessage(String message) {
            lastErrors.forEach(index -> errors.set(index, message));
            return this;
        }

        private boolean skipped(Instance instance) {
            return switch (skip) {
                case NONE -> false;
                case UNDEFINED -> !instance.present;
                case NULL -> !instance.present || instance.value == null;
                case FALSY -> !instance.present || !JsValues.truthy(instance.value);
            };
        }

        private void fail() {
            lastErrors.add(errors.size());
            errors.add(INVALID);
        }

        /** A custom validator: sees the raw value. */
        public Chain check(Predicate<Object> test) {
            lastErrors.clear();
            for (Instance instance : instances) {
                if (!skipped(instance) && !test.test(instance.value)) {
                    fail();
                }
            }
            return this;
        }

        /** A standard validator: sees each element of an array, as express-validator's toString text. */
        private Chain standard(Predicate<String> test) {
            lastErrors.clear();
            for (Instance instance : instances) {
                if (skipped(instance)) {
                    continue;
                }
                for (Object value : elements(instance.value)) {
                    if (!test.test(JsValues.stringify(value))) {
                        fail();
                    }
                }
            }
            return this;
        }

        private Chain sanitize(Function<String, Object> sanitizer) {
            for (Instance instance : instances) {
                if (skipped(instance)) {
                    continue;
                }
                Object cleaned = instance.value instanceof List<?> list
                        ? new ArrayList<>(list.stream().map(item -> sanitizer.apply(JsValues.stringify(item))).toList())
                        : sanitizer.apply(JsValues.stringify(instance.value));
                instance.value = cleaned;
                instance.present = true;
                instance.writer.accept(cleaned);
            }
            return this;
        }

        public Chain isString() {
            return check(value -> value instanceof String);
        }

        public Chain isArray(int max) {
            return check(value -> value instanceof List<?> list && list.size() <= max);
        }

        public Chain isObject() {
            return check(value -> value instanceof Map<?, ?>);
        }

        /** Length in characters as validator.js counts them (a surrogate pair or variation selector is not extra). */
        public Chain isLength(int min, int max) {
            return standard(text -> {
                int length = text.codePointCount(0, text.length()) - (int) text.chars().filter(c -> c == '️' || c == '︎').count();
                return length >= min && length <= max;
            });
        }

        public Chain isIn(Collection<String> values) {
            return standard(values::contains);
        }

        public Chain isBoolean() {
            return standard(BOOLEANS::contains);
        }

        public Chain isMongoId() {
            return standard(text -> MONGO_ID.matcher(text).matches());
        }

        public Chain isInt(long min, long max) {
            return standard(text -> {
                if (!INT.matcher(text).matches()) {
                    return false;
                }
                BigDecimal number = new BigDecimal(text.startsWith("+") ? text.substring(1) : text);
                return number.compareTo(BigDecimal.valueOf(min)) >= 0 && number.compareTo(BigDecimal.valueOf(max)) <= 0;
            });
        }

        public Chain isAlpha() {
            return standard(text -> ALPHA.matcher(text).matches());
        }

        /** {@code matches(regex)}: the regex is searched for, like {@code RegExp.test}. */
        public Chain matches(Pattern pattern) {
            return standard(text -> pattern.matcher(text).find());
        }

        public Chain isEmail() {
            return standard(RequestChecks::isEmail);
        }

        public Chain trim() {
            return sanitize(JsValues::trim);
        }

        /** {@code toBoolean()}: everything except "0", "false" and "" is true. */
        public Chain toBoolean() {
            return sanitize(text -> !text.equals("0") && !text.equalsIgnoreCase("false") && !text.isEmpty());
        }
    }

    private static List<?> elements(Object value) {
        return value instanceof List<?> list ? list : Collections.singletonList(value);
    }

    /** validator.js isEmail with its defaults (a top-level domain is required, UTF-8 local parts are allowed). */
    static boolean isEmail(String text) {
        if (text.length() > 254) {
            return false;
        }
        int at = text.lastIndexOf('@');
        if (at <= 0 || at == text.length() - 1) {
            return false;
        }
        String user = text.substring(0, at);
        String domain = text.substring(at + 1);
        if (user.getBytes(StandardCharsets.UTF_8).length > 64 || domain.getBytes(StandardCharsets.UTF_8).length > 254 || !isDomain(domain)) {
            return false;
        }
        if (user.startsWith("\"") && user.endsWith("\"") && user.length() > 1) {
            return user.substring(1, user.length() - 1).chars().noneMatch(c -> c == '"' || c == '\\' || c < 32);
        }
        for (String part : user.split("\\.", -1)) {
            if (!EMAIL_PART.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        String tld = labels[labels.length - 1];
        if (!TLD.matcher(tld).matches() || tld.chars().anyMatch(Character::isWhitespace) || tld.chars().allMatch(Character::isDigit)) {
            return false;
        }
        for (String label : labels) {
            if (label.length() > 63 || !LABEL.matcher(label).matches() || label.startsWith("-") || label.endsWith("-")
                    || label.chars().anyMatch(c -> c >= 0xFF01 && c <= 0xFF5E) || label.toLowerCase(Locale.ROOT).contains("_")) {
                return false;
            }
        }
        return true;
    }
}
