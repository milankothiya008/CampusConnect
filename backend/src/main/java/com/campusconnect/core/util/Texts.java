package com.campusconnect.core.util;

import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.query.Criteria;

/** Text helpers shared by services (utils/Query.js escapeRegex/searchRegex and friends). */
public final class Texts {

    private static final Pattern SPECIAL = Pattern.compile("[.*+?^${}()|\\[\\]\\\\]");

    private Texts() {
    }

    /** Escapes a value so it matches literally inside a regular expression. */
    public static String escapeRegex(String value) {
        return SPECIAL.matcher(String.valueOf(value)).replaceAll("\\\\$0");
    }

    /** The case-insensitive "contains" pattern used by search boxes (escaped, trimmed, at most 100 chars). */
    public static String searchPattern(String value) {
        String escaped = escapeRegex(String.valueOf(value).trim());
        return escaped.length() > 100 ? escaped.substring(0, 100) : escaped;
    }

    /** {@code field ~ /value/i} for a search box. */
    public static Criteria search(String field, String value) {
        return Criteria.where(field).regex(searchPattern(value), "i");
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Trimmed text cut to {@code max} characters ("" for null). */
    public static String clean(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
