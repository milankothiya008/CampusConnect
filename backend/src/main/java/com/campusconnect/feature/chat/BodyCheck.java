package com.campusconnect.feature.chat;

import com.campusconnect.core.error.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * express-validator rules for the few PATCH bodies where "left out" and "null" mean different things (a group photo
 * set to null is removed; an election's {@code opensAt: null} opens it now), so they are read as plain JSON maps.
 * Messages are collected in rule order and joined with ". ", like middleware/Validate.js.
 */
public final class BodyCheck {

    private static final Set<String> BOOLEAN_TEXT = Set.of("true", "false", "1", "0");

    private final Map<String, Object> body;
    private final List<String> errors = new ArrayList<>();

    private BodyCheck(Map<String, Object> body) {
        this.body = body == null ? Map.of() : body;
    }

    public static BodyCheck of(Map<String, Object> body) {
        return new BodyCheck(body);
    }

    /** validator.js isLength: code points, not counting emoji presentation selectors. */
    static int length(String value) {
        int selectors = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '︎' || c == '️') {
                selectors++;
            }
        }
        return value.codePointCount(0, value.length()) - selectors;
    }

    /** {@code body(field).optional().isString().isLength({ max })}. */
    public BodyCheck optionalString(String field, int max, String message) {
        if (body.containsKey(field) && !(body.get(field) instanceof String text && length(text) <= max)) {
            errors.add(message);
        }
        return this;
    }

    /** {@code body(field).optional().isBoolean()}: true/false, or their text and 1/0 forms. */
    public BodyCheck optionalBoolean(String field, String message) {
        if (body.containsKey(field)) {
            Object value = body.get(field);
            boolean scalar = value instanceof Boolean || value instanceof Number || value instanceof String;
            if (!scalar || !BOOLEAN_TEXT.contains(JsValues.string(value))) {
                errors.add(message);
            }
        }
        return this;
    }

    /** {@code body(field).optional().isArray({ max })}. */
    public BodyCheck optionalArray(String field, int max, String message) {
        if (body.containsKey(field) && !(body.get(field) instanceof List<?> list && list.size() <= max)) {
            errors.add(message);
        }
        return this;
    }

    /**
     * A value checked by {@code valid}; absent values are skipped, and with {@code skipFalsy} also null, "" and other
     * falsy ones ({@code optional({ values: "falsy" })}).
     */
    public BodyCheck optional(String field, boolean skipFalsy, Predicate<Object> valid, String message) {
        if (body.containsKey(field) && !(skipFalsy && !JsValues.truthy(body.get(field))) && !valid.test(body.get(field))) {
            errors.add(message);
        }
        return this;
    }

    /** Throws 400 VALIDATION_ERROR with every message when a rule failed. */
    public void validate() {
        if (!errors.isEmpty()) {
            throw AppException.badRequest(String.join(". ", errors));
        }
    }
}
