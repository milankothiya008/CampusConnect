package com.campusconnect.feature.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;

/**
 * Request bodies are loosely typed JSON, and the original services relied on JavaScript's conversions
 * ({@code Boolean(x)}, {@code Number(x)}, {@code String(x ?? "")}, {@code new Date(x)}). These helpers give the same
 * answers, so a value the old API accepted is accepted here and one it refused is refused with the same message.
 */
public final class Values {

    private static final Pattern NUMERIC = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private Values() {
    }

    /** JavaScript truthiness: null, false, 0, NaN and "" are false. */
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

    /** JavaScript {@code Number(value)}: NaN when it is not a number. */
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
            String trimmed = text.strip();
            if (trimmed.isEmpty()) {
                return 0;
            }
            return NUMERIC.matcher(trimmed).matches() ? Double.parseDouble(trimmed) : Double.NaN;
        }
        return Double.NaN;
    }

    public static boolean isInteger(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value);
    }

    /** {@code String(value ?? "")}. */
    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** {@code String(value ?? "").trim().slice(0, max)}. */
    public static String clean(Object value, int max) {
        String trimmed = text(value).strip();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    /** A number as JavaScript prints it in JSON: 10000 rather than 10000.0. */
    public static Number jsNumber(double value) {
        if (isInteger(value) && Math.abs(value) < 1e15) {
            return (long) value;
        }
        return value;
    }

    /** A JavaScript array, or null when the value is not one. */
    public static List<?> list(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return value instanceof Collection<?> collection ? List.copyOf(collection) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** An id as a hex string, whatever form it was stored in (ObjectId, string, populated object). */
    public static String id(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ObjectId objectId) {
            return objectId.toHexString();
        }
        if (value instanceof Map<?, ?> map && map.get("_id") != null) {
            return id(map.get("_id"));
        }
        return String.valueOf(value);
    }

    /** {@code new Date(value)}: null when it is not a valid date. Times without an offset are read as UTC. */
    public static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the shorter forms
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // date only?
        }
        if (ISO_DATE.matcher(text).matches()) {
            try {
                return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    /** The ObjectId of a reference (null when missing or malformed), for references written into free-form maps. */
    public static ObjectId objectId(Object value) {
        String id = id(value);
        return id != null && ObjectId.isValid(id) ? new ObjectId(id) : null;
    }
}
