package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.Values.clean;
import static com.campusconnect.feature.event.Values.isInteger;
import static com.campusconnect.feature.event.Values.number;
import static com.campusconnect.feature.event.Values.text;
import static com.campusconnect.feature.event.Values.truthy;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.venue.Venue;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.bson.types.ObjectId;

/**
 * The rules an event's details follow (the pure helpers of EventService.js): its schedule, eligibility, budget,
 * contact, team settings and capacity, the registration window, and how an edit is compared with the live event.
 */
public final class EventRules {

    /** Longest event: a week (fests and multi-day hackathons fit; anything longer is several events). */
    public static final int MAX_EVENT_DAYS = 7;

    /** Details a club can edit (all of them are reviewed by the mentor once the event has been approved). */
    public static final List<String> EDIT_FIELDS = List.of(
            "title", "shortDescription", "description", "category", "poster", "rules", "contact", "maxParticipants", "eligibility", "venue",
            "eventDate", "endDate", "startTime", "endTime", "registrationStart", "registrationEnd", "organizer", "participationMode",
            "minTeamSize", "maxTeamSize", "budgetItems", "equipment", "budgetNote");

    public static final List<String> TEAM_FIELDS = List.of("participationMode", "minTeamSize", "maxTeamSize");

    /** startAt/endAt follow from eventDate + times, so they are stored with a change but not listed separately. */
    public static final List<String> DERIVED_FIELDS = List.of("startAt", "endAt");

    public static final Map<String, String> FIELD_LABELS = labels();

    private EventRules() {
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("title", "title");
        labels.put("shortDescription", "short description");
        labels.put("description", "description");
        labels.put("category", "category");
        labels.put("poster", "poster");
        labels.put("rules", "rules");
        labels.put("contact", "contact details");
        labels.put("maxParticipants", "participant limit");
        labels.put("eligibility", "eligibility");
        labels.put("venue", "venue");
        labels.put("eventDate", "date");
        labels.put("endDate", "end date");
        labels.put("startTime", "start time");
        labels.put("endTime", "end time");
        labels.put("registrationStart", "registration opening");
        labels.put("registrationEnd", "registration deadline");
        labels.put("organizer", "organizer");
        labels.put("participationMode", "team or individual entry");
        labels.put("minTeamSize", "minimum team size");
        labels.put("maxTeamSize", "maximum team size");
        labels.put("budgetItems", "budget");
        labels.put("equipment", "equipment");
        labels.put("budgetNote", "budget note");
        return Map.copyOf(labels);
    }

    private static AppException invalid(String message) {
        return AppException.badRequest(message, ErrorCode.VALIDATION_ERROR);
    }

    // ---------------------------------------------------------------- schedule

    /** The event's dates and times, worked out from the form (EventService.buildSchedule). */
    public record Schedule(Instant eventDate, Instant endDate, String startTime, String endTime, Instant startAt, Instant endAt,
            Instant registrationStart, Instant registrationEnd) {

        /** The fields as they are stored on the event, in form order. */
        public Map<String, Object> fields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("eventDate", eventDate);
            fields.put("endDate", endDate);
            fields.put("startTime", startTime);
            fields.put("endTime", endTime);
            fields.put("startAt", startAt);
            fields.put("endAt", endAt);
            fields.put("registrationStart", registrationStart);
            fields.put("registrationEnd", registrationEnd);
            return fields;
        }
    }

    /** toDateKey: "YYYY-MM-DD" of a form date, an ISO string or a stored (UTC midnight) date. */
    public static String dateKey(Object value) {
        if (value instanceof Instant instant) {
            return CampusTime.toDateKey(instant);
        }
        if (value instanceof Date date) {
            return CampusTime.toDateKey(date.toInstant());
        }
        if (value instanceof Number millis) {
            return CampusTime.toDateKey(Instant.ofEpochMilli(millis.longValue()));
        }
        if (value == null || "".equals(value)) {
            throw invalid("Invalid date");
        }
        return CampusTime.toDateKey(String.valueOf(value));
    }

    public static Schedule buildSchedule(Object eventDate, Object endDate, Object startTime, Object endTime, Object registrationStart,
            Object registrationEnd) {
        // Events may end on a later day (overnight hackathons, two-day fests); endDate defaults to the start date.
        String endKey = truthy(endDate) ? dateKey(endDate) : dateKey(eventDate);
        String startKey = dateKey(eventDate);
        Instant startAt = CampusTime.combineDateAndTime(startKey, text(startTime));
        Instant endAt = CampusTime.combineDateAndTime(endKey, text(endTime));
        Instant now = Instant.now();

        if (endKey.compareTo(startKey) < 0) {
            throw invalid("The event can't end before the day it starts");
        }
        if (!startAt.isBefore(endAt)) {
            throw invalid("Event end time must be after the start time");
        }
        if (Duration.between(startAt, endAt).compareTo(Duration.ofDays(MAX_EVENT_DAYS)) > 0) {
            throw invalid("An event can last at most " + MAX_EVENT_DAYS + " days");
        }
        if (!startAt.isAfter(now)) {
            throw invalid("Event must be scheduled in the future");
        }

        Instant opens = truthy(registrationStart) ? Values.instant(registrationStart) : now;
        Instant closes = Values.instant(registrationEnd);
        if (opens == null || closes == null) {
            throw invalid("Invalid registration window");
        }
        if (!opens.isBefore(closes)) {
            throw invalid("Registration deadline must be after registration opens");
        }
        if (closes.isAfter(startAt)) {
            throw invalid("Registration must close before the event starts");
        }
        // An opening time in the past just means "open as soon as it is published"; a past deadline is never valid.
        if (!closes.isAfter(now)) {
            throw invalid("Registration deadline must be in the future");
        }
        return new Schedule(CampusTime.dateKeyToInstant(startKey), endKey.equals(startKey) ? null : CampusTime.dateKeyToInstant(endKey),
                text(startTime), text(endTime), startAt, endAt, opens, closes);
    }

    // ---------------------------------------------------------------- eligibility, budget, contact

    /** Who may register: department codes (upper-case) and two-digit batch codes, each sorted, plus a note. */
    public static Map<String, Object> normalizeEligibility(Object raw) {
        Map<String, Object> eligibility = Values.map(raw) == null ? Map.of() : Values.map(raw);
        List<String> departments = sortedUnique(eligibility.get("departments"), value -> value.strip().toUpperCase(Locale.ROOT), value -> !value.isEmpty());
        List<String> batches = sortedUnique(eligibility.get("batches"), String::strip, value -> value.matches("^\\d{2}$"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("departments", departments);
        result.put("batches", batches);
        result.put("notes", truthy(eligibility.get("notes")) ? text(eligibility.get("notes")).strip() : "");
        return result;
    }

    @SuppressWarnings("unchecked")
    public static List<String> departmentsOf(Map<String, Object> eligibility) {
        return eligibility == null ? List.of() : (List<String>) eligibility.getOrDefault("departments", List.of());
    }

    private static List<String> sortedUnique(Object values, java.util.function.UnaryOperator<String> clean, java.util.function.Predicate<String> keep) {
        List<?> list = Values.list(values);
        if (list == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        list.stream().map(value -> clean.apply(text(value))).filter(keep).forEach(unique::add);
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(null);
        return sorted;
    }

    /** The budget and equipment the club asks for (approved by the mentor with the event). Rows have no _id yet. */
    public record Budget(List<Map<String, Object>> budgetItems, List<Map<String, Object>> equipment, String budgetNote) {
    }

    public static Budget normalizeBudget(Object budgetItems, Object equipment, Object budgetNote) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<?> itemRows = Values.list(budgetItems);
        if (itemRows != null) {
            for (int index = 0; index < Math.min(40, itemRows.size()); index++) {
                Map<String, Object> row = Values.map(itemRows.get(index));
                Object rawItem = row == null ? null : row.get("item");
                String item = (truthy(rawItem) ? text(rawItem) : "").strip();
                double quantity = number(row == null || row.get("quantity") == null ? 1 : row.get("quantity"));
                double unitCost = number(row == null || row.get("unitCost") == null ? 0 : row.get("unitCost"));
                if (item.isEmpty()) {
                    throw invalid("Budget line " + (index + 1) + ": say what it's for");
                }
                if (!Double.isFinite(quantity) || quantity < 1) {
                    throw invalid("Budget line " + (index + 1) + ": quantity must be at least 1");
                }
                if (!Double.isFinite(unitCost) || unitCost < 0) {
                    throw invalid("Budget line " + (index + 1) + ": cost can't be negative");
                }
                long roundedQuantity = Math.round(quantity);
                double roundedCost = Math.round(unitCost * 100) / 100.0;
                assertAtMost("quantity", roundedQuantity, 100_000);
                assertAtMost("unitCost", roundedCost, 10_000_000);
                Map<String, Object> clean = new LinkedHashMap<>();
                clean.put("item", item.length() > 120 ? item.substring(0, 120) : item);
                clean.put("quantity", (int) roundedQuantity);
                clean.put("unitCost", roundedCost);
                clean.put("note", clean(truthy(row.get("note")) ? row.get("note") : "", 200));
                items.add(clean);
            }
        }
        List<Map<String, Object>> gear = new ArrayList<>();
        List<?> gearRows = Values.list(equipment);
        if (gearRows != null) {
            for (int index = 0; index < Math.min(40, gearRows.size()); index++) {
                Map<String, Object> row = Values.map(gearRows.get(index));
                Object rawName = row == null ? null : row.get("name");
                String name = (truthy(rawName) ? text(rawName) : "").strip();
                double quantity = number(row == null || row.get("quantity") == null ? 1 : row.get("quantity"));
                if (name.isEmpty()) {
                    throw invalid("Equipment line " + (index + 1) + ": name the item");
                }
                if (!Double.isFinite(quantity) || quantity < 1) {
                    throw invalid("Equipment line " + (index + 1) + ": quantity must be at least 1");
                }
                long roundedQuantity = Math.round(quantity);
                assertAtMost("quantity", roundedQuantity, 10_000);
                Map<String, Object> clean = new LinkedHashMap<>();
                clean.put("name", name.length() > 120 ? name.substring(0, 120) : name);
                clean.put("quantity", (int) roundedQuantity);
                clean.put("note", clean(truthy(row.get("note")) ? row.get("note") : "", 200));
                gear.add(clean);
            }
        }
        return new Budget(items, gear, clean(truthy(budgetNote) ? budgetNote : "", 1000));
    }

    /** The schema maximums Mongoose enforced when saving (budget lines, equipment, expenses). */
    static void assertAtMost(String path, double value, double max) {
        if (value > max) {
            throw invalid("Validation failed: Path `" + path + "` (" + Values.jsNumber(value) + ") is more than maximum allowed value ("
                    + Values.jsNumber(max) + ").");
        }
    }

    public static Map<String, Object> normalizeContact(Object raw) {
        Map<String, Object> contact = Values.map(raw) == null ? Map.of() : Values.map(raw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", (truthy(contact.get("name")) ? text(contact.get("name")) : "").strip());
        result.put("email", (truthy(contact.get("email")) ? text(contact.get("email")) : "").strip().toLowerCase(Locale.ROOT));
        result.put("phone", (truthy(contact.get("phone")) ? text(contact.get("phone")) : "").strip());
        return result;
    }

    // ---------------------------------------------------------------- capacity and teams

    /** For team events the limit counts teams, so the venue must fit that many full teams. Null = unlimited. */
    public static Integer assertCapacityFits(Object maxParticipants, Venue venue, int registeredCount, int teamSize) {
        if (maxParticipants == null || "".equals(maxParticipants)) {
            return null;
        }
        double value = number(maxParticipants);
        if (!isInteger(value) || value < 1) {
            throw invalid("Participant limit must be a positive whole number");
        }
        long limit = (long) value;
        if (venue != null && limit * teamSize > venue.getCapacity()) {
            throw invalid(teamSize > 1
                    ? limit + " teams of up to " + teamSize + " would exceed " + venue.getName() + "'s capacity of " + venue.getCapacity()
                    : "Participant limit exceeds " + venue.getName() + "'s capacity of " + venue.getCapacity());
        }
        if (limit < registeredCount) {
            throw invalid("The limit cannot be lower than current registrations (" + registeredCount + ")");
        }
        return (int) limit;
    }

    public record TeamSettings(ParticipationMode participationMode, int minTeamSize, int maxTeamSize) {

        public Map<String, Object> fields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("participationMode", participationMode.name());
            fields.put("minTeamSize", minTeamSize);
            fields.put("maxTeamSize", maxTeamSize);
            return fields;
        }

        boolean differsFrom(Event event) {
            return participationMode != event.getParticipationMode() || minTeamSize != event.getMinTeamSize() || maxTeamSize != event.getMaxTeamSize();
        }
    }

    public static TeamSettings normalizeTeamSettings(EventPayload payload, Event current) {
        Object rawMode = payload.get("participationMode");
        String mode = rawMode != null ? text(rawMode)
                : current != null && current.getParticipationMode() != null ? current.getParticipationMode().name() : ParticipationMode.INDIVIDUAL.name();
        if (!ParticipationMode.TEAM.name().equals(mode)) {
            return new TeamSettings(ParticipationMode.INDIVIDUAL, 1, 1);
        }
        boolean wasTeam = current != null && current.getParticipationMode() == ParticipationMode.TEAM;
        double min = number(payload.getOr("minTeamSize", wasTeam ? current.getMinTeamSize() : 2));
        double max = number(payload.getOr("maxTeamSize", wasTeam ? current.getMaxTeamSize() : 4));
        if (!isInteger(min) || !isInteger(max) || min < 1 || max < 2 || max > 20 || min > max) {
            throw invalid("Teams can have 2 to 20 members, and the minimum team size can't be above the maximum");
        }
        return new TeamSettings(ParticipationMode.TEAM, (int) min, (int) max);
    }

    // ---------------------------------------------------------------- registration window

    /** What the registration window depends on, kept with cached feed cards so the state is worked out fresh. */
    public record Window(EventStatus status, boolean registrationClosed, Instant registrationStart, Instant registrationEnd, Instant startAt,
            Integer maxParticipants, int registeredCount) {

        public static Window of(Event event) {
            return new Window(event.getStatus(), event.isRegistrationClosed(), event.getRegistrationStart(), event.getRegistrationEnd(),
                    event.getStartAt(), event.getMaxParticipants(), event.getRegisteredCount());
        }
    }

    /** UNAVAILABLE, CLOSED, NOT_OPEN, FULL or OPEN (EventService.registrationWindowState). */
    public static String registrationWindowState(Window event, Instant now) {
        if (event.status() != EventStatus.PUBLISHED) {
            return "UNAVAILABLE";
        }
        if (event.registrationClosed() || (event.registrationEnd() != null && now.isAfter(event.registrationEnd()))
                || (event.startAt() != null && !now.isBefore(event.startAt()))) {
            return "CLOSED";
        }
        if (event.registrationStart() != null && now.isBefore(event.registrationStart())) {
            return "NOT_OPEN";
        }
        if (event.maxParticipants() != null && event.maxParticipants() != 0 && event.registeredCount() >= event.maxParticipants()) {
            return "FULL";
        }
        return "OPEN";
    }

    public static String registrationWindowState(Event event) {
        return registrationWindowState(Window.of(event), Instant.now());
    }

    // ---------------------------------------------------------------- comparing an edit with the live event

    /** "title, venue and date" for a list of changed fields. */
    public static String labelList(List<String> fields) {
        List<String> labels = fields.stream().map(field -> FIELD_LABELS.getOrDefault(field, field)).toList();
        if (labels.size() > 1) {
            return String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
        }
        return labels.isEmpty() ? "" : labels.get(0);
    }

    /**
     * A canonical form for comparing a proposed value with the live one: blanks are null, dates and ids are strings,
     * numbers compare by value and embedded rows are compared by their content, without their {@code _id}.
     */
    public static Object comparable(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Instant instant) {
            return Instant.ofEpochMilli(instant.toEpochMilli()).toString();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof ObjectId id) {
            return id.toHexString();
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isFinite(d) ? new BigDecimal(number.toString()).stripTrailingZeros().toPlainString() : String.valueOf(d);
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> {
                if (!"_id".equals(key)) {
                    sorted.put(String.valueOf(key), comparable(nested));
                }
            });
            return sorted;
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(EventRules::comparable).toList();
        }
        return value;
    }

    public static boolean sameValue(Object a, Object b) {
        return Objects.equals(comparable(a), comparable(b));
    }

    /** Forms work in whole minutes, so a stored time with seconds still counts as unchanged. */
    public static boolean sameInstant(Object value, Instant current) {
        Instant proposed = Values.instant(value);
        if (proposed == null || current == null) {
            return false;
        }
        return Math.floorDiv(proposed.toEpochMilli(), 60_000) == Math.floorDiv(current.toEpochMilli(), 60_000);
    }

    /** The end date as the form sends it: null when the event ends on its start date. */
    public static String endDateKeyOf(Object endDate, Object eventDate) {
        if (!truthy(endDate)) {
            return null;
        }
        String key = dateKey(endDate);
        return key.equals(dateKey(eventDate)) ? null : key;
    }

    /** Whether the form moves the event or its registration window. */
    public static boolean scheduleChanged(Event event, EventPayload payload) {
        Object eventDate = truthy(payload.get("eventDate")) ? payload.get("eventDate") : event.getEventDate();
        return (payload.has("eventDate") && !Objects.equals(payload.get("eventDate"), dateKey(event.getEventDate())))
                || (payload.has("endDate") && !Objects.equals(endDateKeyOf(payload.get("endDate"), eventDate), endDateKeyOf(event.getEndDate(), eventDate)))
                || (payload.has("startTime") && !Objects.equals(payload.get("startTime"), event.getStartTime()))
                || (payload.has("endTime") && !Objects.equals(payload.get("endTime"), event.getEndTime()))
                || (truthy(payload.get("registrationStart")) && !sameInstant(payload.get("registrationStart"), event.getRegistrationStart()))
                || (payload.has("registrationEnd") && !sameInstant(payload.get("registrationEnd"), event.getRegistrationEnd()));
    }

    public static void assertInFuture(Event event, String message) {
        if (!event.getStartAt().isAfter(Instant.now())) {
            throw AppException.conflict(message, ErrorCode.INVALID_STATE);
        }
    }

    public static void assertStatus(Event event, Collection<EventStatus> allowed, String message) {
        if (!allowed.contains(event.getStatus())) {
            throw AppException.conflict(message, ErrorCode.INVALID_STATE);
        }
    }
}
