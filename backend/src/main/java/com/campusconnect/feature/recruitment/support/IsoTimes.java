package com.campusconnect.feature.recruitment.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Times sent by the browser, read the way {@code new Date(value)} read them: ISO-8601 instants with an offset or
 * "Z", local date-times (taken as UTC, the servers' zone) and plain dates (UTC midnight).
 */
public final class IsoTimes {

    /**
     * validator.js {@code isISO8601} (non-strict), so the strings the original routes accepted still pass the
     * request validation.
     */
    public static final String ISO8601 = "^([\\+-]?\\d{4}(?!\\d{2}\\b))((-?)((0[1-9]|1[0-2])(\\3([12]\\d|0[1-9]|3[01]))?|W([0-4]\\d|5[0-3])(-?[1-7])?"
            + "|(00[1-9]|0[1-9]\\d|[12]\\d{2}|3([0-5]\\d|6[1-6])))([T\\s]((([01]\\d|2[0-3])((:?)[0-5]\\d)?|24:?00)([\\.,]\\d+(?!:))?)?"
            + "(\\17[0-5]\\d([\\.,]\\d+)?)?([zZ]|([\\+-])([01]\\d|2[0-3]):?([0-5]\\d)?)?)?)?$";

    private static final Pattern ISO = Pattern.compile(ISO8601);
    private static final Pattern SPACE_SEPARATOR = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d)");
    private static final Pattern COMPACT_OFFSET = Pattern.compile("([+-]\\d{2})(\\d{2})$");
    private static final Pattern HOUR_OFFSET = Pattern.compile("(T[^+-]*[+-]\\d{2})$");

    private IsoTimes() {
    }

    public static boolean isIso8601(String value) {
        return value != null && ISO.matcher(value).matches();
    }

    /** The instant (to the millisecond, like a JavaScript Date), or null when the text is not a date ("Invalid Date"). */
    public static Instant parse(String raw) {
        Instant parsed = parseExact(raw);
        return parsed == null ? null : parsed.truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant parseExact(String raw) {
        if (raw == null) {
            return null;
        }
        String value = Js.trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        value = SPACE_SEPARATOR.matcher(value).replaceFirst("$1T$2");
        if (value.endsWith("z")) {
            value = value.substring(0, value.length() - 1) + "Z";
        }
        if (value.contains("T")) {
            value = COMPACT_OFFSET.matcher(value).replaceFirst("$1:$2");
            value = HOUR_OFFSET.matcher(value).replaceFirst("$1:00");
        }
        try {
            return OffsetDateTime.parse(value.toUpperCase(Locale.ROOT)).toInstant();
        } catch (DateTimeParseException ignored) {
            // not an instant with an offset
        }
        try {
            return LocalDateTime.parse(value.toUpperCase(Locale.ROOT)).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // not a local date-time
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** {@code new Date(value)} for a JSON value: null is the epoch, numbers are epoch milliseconds. */
    public static Instant toInstant(Object value) {
        if (value == null) {
            return Instant.EPOCH;
        }
        if (value instanceof Instant instant) {
            return instant.truncatedTo(ChronoUnit.MILLIS);
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            double millis = number.doubleValue();
            return Double.isNaN(millis) || Double.isInfinite(millis) ? null : Instant.ofEpochMilli((long) millis);
        }
        if (value instanceof String text) {
            return parse(text);
        }
        return null;
    }
}
