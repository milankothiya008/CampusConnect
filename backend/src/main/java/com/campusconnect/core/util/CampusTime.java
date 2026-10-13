package com.campusconnect.core.util;

import com.campusconnect.core.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dates are stored as UTC instants; people read and enter them in the university's timezone
 * (utils/CampusTime.js and the date helpers of utils/UniversityRules.js). The offset is configured once at
 * start-up from {@code app.university.timezone-offset}.
 */
public final class CampusTime {

    private static volatile ZoneOffset zone = ZoneOffset.of("+05:30");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    // en-GB like the Node app ("Sat, 5 Sept 2026").
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.UK);
    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern DATE_KEY = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}).*");

    private CampusTime() {
    }

    public static void configure(String offset) {
        zone = ZoneOffset.of(offset);
    }

    public static ZoneOffset zone() {
        return zone;
    }

    /** "14:30" in university time. */
    public static String formatTime(Instant instant) {
        return TIME.format(instant.atOffset(zone));
    }

    /** "Tue, 6 Oct 2026" in university time. */
    public static String formatDate(Instant instant) {
        return DATE.format(instant.atOffset(zone));
    }

    /** "Tue, 6 Oct 2026 · 18:00–23:30", or both dates when the event spans days. */
    public static String formatSchedule(Instant start, Instant end) {
        return formatDate(start).equals(formatDate(end))
                ? formatDate(start) + " · " + formatTime(start) + "–" + formatTime(end)
                : formatDate(start) + ", " + formatTime(start) + " – " + formatDate(end) + ", " + formatTime(end);
    }

    /** Normalises a calendar date (YYYY-MM-DD…, or an ISO instant) to its YYYY-MM-DD key. */
    public static String toDateKey(String value) {
        if (value != null) {
            Matcher matcher = DATE_KEY.matcher(value.trim());
            if (matcher.matches()) {
                try {
                    return LocalDate.parse(matcher.group(1)).toString();
                } catch (DateTimeParseException ignored) {
                    // fall through to the instant parser
                }
            }
            try {
                return Instant.parse(value.trim()).atOffset(ZoneOffset.UTC).toLocalDate().toString();
            } catch (DateTimeParseException ignored) {
                // invalid below
            }
        }
        throw AppException.badRequest("Invalid date");
    }

    public static String toDateKey(Instant value) {
        if (value == null) {
            throw AppException.badRequest("Invalid date");
        }
        return value.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** The calendar date stored at UTC midnight (how Mongoose stored eventDate). */
    public static Instant dateKeyToInstant(String value) {
        return LocalDate.parse(toDateKey(value)).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** "Tue, 6 Oct 2026" for a calendar date key. */
    public static String formatDateKey(String value) {
        return DATE.format(LocalDate.parse(toDateKey(value)));
    }

    /** A calendar date plus an HH:mm wall-clock time in the university's timezone. */
    public static Instant combineDateAndTime(String dateValue, String timeValue) {
        String key = toDateKey(dateValue);
        String time = timeValue == null ? "" : timeValue.trim();
        Matcher matcher = HH_MM.matcher(time);
        if (!matcher.matches()) {
            throw AppException.badRequest("Time must be in HH:mm 24-hour format");
        }
        return LocalDate.parse(key).atTime(LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)))).toInstant(zone);
    }

    public static boolean intervalsOverlap(Instant startA, Instant endA, Instant startB, Instant endB) {
        return startA.isBefore(endB) && endA.isAfter(startB);
    }
}
