package com.campusconnect.feature.election;

import com.campusconnect.feature.chat.JsValues;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code new Date(value)} for the ISO-8601 dates the election form sends ("2026-10-14T09:30:00.000Z", with an offset,
 * date only, ...): millisecond precision, and times without a zone read as UTC, the zone the original server ran in.
 */
final class JsDates {

    private static final Pattern YEAR = Pattern.compile("\\d{4}");

    /** The ISO forms JavaScript's Date understands, most specific first. */
    private static final List<Function<String, Instant>> FORMS = List.of(
            text -> OffsetDateTime.parse(text).toInstant(),
            text -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC),
            text -> LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant(),
            text -> YearMonth.parse(text).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
            text -> {
                if (!YEAR.matcher(text).matches()) {
                    throw new DateTimeParseException("Not a year", text, 0);
                }
                return Year.parse(text).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            });

    private JsDates() {
    }

    /** The instant, or null when the value is no date (JavaScript's "Invalid Date"). */
    static Instant parse(Object value) {
        if (value instanceof Instant instant) {
            return JsValues.millis(instant);
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (!(value instanceof String text) || text.isEmpty()) {
            return null;
        }
        String iso = text.length() > 10 && text.charAt(10) == ' ' ? text.substring(0, 10) + "T" + text.substring(11) : text;
        for (Function<String, Instant> form : FORMS) {
            try {
                return JsValues.millis(form.apply(iso));
            } catch (DateTimeParseException notThisForm) {
                // try the next form
            }
        }
        return null;
    }
}
