package com.campusconnect.feature.venue;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundMode;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.domain.venue.VenueStatus;
import com.campusconnect.domain.venue.VenueType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Venues, labs and bookings (services/VenueService.js). Labs belong to departments; an event's audience is its
 * own department list if it has one, otherwise its club's departments; an all-department club with an event open
 * to everyone may use any lab. Bookings are serialised per venue with a short Redis lock, so two replicas can never
 * both pass the conflict check and double-book.
 */
@Service
public class VenueService {

    /** "Every department" audience. */
    public static final List<String> ALL = Collections.unmodifiableList(new ArrayList<>(List.of("ALL")));

    static final Duration LOCK_TTL = Duration.ofSeconds(10);
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final MongoOperations mongo;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public VenueService(MongoOperations mongo, StringRedisTemplate redis, JsonMapper json) {
        this.mongo = mongo;
        this.redis = redis;
        this.json = json;
    }

    // ---------------------------------------------------------------- labs and audiences

    public static boolean isAll(List<String> audience) {
        return audience == ALL;
    }

    /** The departments an event (or a club's recruitment) is for: a list of codes, or {@link #ALL}. */
    public static List<String> audienceOf(Club club, List<String> eligibilityDepartments) {
        List<String> listed = eligibilityDepartments == null ? List.of() : eligibilityDepartments.stream()
                .filter(code -> code != null && !code.isBlank()).map(code -> code.toUpperCase(Locale.ROOT)).toList();
        if (!listed.isEmpty()) {
            return listed;
        }
        if (club == null || club.isAllDepartments()) {
            return ALL;
        }
        return club.getDepartmentCodes().stream().map(code -> code.toUpperCase(Locale.ROOT)).toList();
    }

    public static List<String> audienceOf(Club club) {
        return audienceOf(club, List.of());
    }

    public static boolean venueFits(Venue venue, List<String> audience) {
        if (venue.getType() != VenueType.LAB || isAll(audience)) {
            return true;
        }
        return venue.getDepartmentCodes().stream().anyMatch(audience::contains);
    }

    static String audienceLabel(List<String> audience) {
        return isAll(audience) ? "every department" : String.join(", ", audience);
    }

    /** Refuses a lab that doesn't belong to one of the audience's departments. */
    public static void assertVenueFitsAudience(Venue venue, List<String> audience) {
        if (!venueFits(venue, audience)) {
            throw AppException.badRequest(venue.getName() + " is a " + String.join(", ", venue.getDepartmentCodes()) + " lab, and this is for "
                    + audienceLabel(audience) + " students. Choose a lab of those departments or a common venue.", ErrorCode.VALIDATION_ERROR);
        }
    }

    /** For listings: ?club=<id>&departments=CE,IT narrows labs to that audience (null = no narrowing). */
    List<String> audienceFromQuery(String clubId, String departmentsParam) {
        List<String> departments = departmentsParam == null ? List.of() : Arrays.stream(departmentsParam.split(","))
                .map(code -> code.trim().toUpperCase(Locale.ROOT)).filter(code -> !code.isEmpty()).toList();
        if ((clubId == null || clubId.isBlank()) && departments.isEmpty()) {
            return null;
        }
        Club club = clubId != null && ObjectId.isValid(clubId) ? mongo.findById(clubId, Club.class) : null;
        return audienceOf(club, departments);
    }

    /** Type and lab departments of a create/update (VenueService.normalizeVenue). */
    record Shape(VenueType type, List<String> departmentCodes) {
    }

    Shape normalize(Object rawType, Object rawDepartments, Venue existing) {
        String typeName = rawType != null ? String.valueOf(rawType).toUpperCase(Locale.ROOT) : existing != null && existing.getType() != null ? existing.getType().name() : VenueType.HALL.name();
        VenueType type;
        try {
            type = VenueType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("Choose the kind of venue", ErrorCode.VALIDATION_ERROR);
        }
        Collection<?> source = rawDepartments instanceof Collection<?> list ? list : rawDepartments == null && existing != null ? existing.getDepartmentCodes() : List.of();
        Set<String> codes = new LinkedHashSet<>();
        source.forEach(code -> {
            String value = String.valueOf(code).trim().toUpperCase(Locale.ROOT);
            if (!value.isEmpty()) {
                codes.add(value);
            }
        });
        if (type == VenueType.LAB) {
            if (codes.isEmpty()) {
                throw AppException.badRequest("Choose the department(s) this lab belongs to", ErrorCode.VALIDATION_ERROR);
            }
            Query query = Query.query(Criteria.where("code").in(codes));
            query.fields().include("code");
            Set<String> known = mongo.find(query, Document.class, "departments").stream().map(row -> row.getString("code")).collect(Collectors.toSet());
            List<String> unknown = codes.stream().filter(code -> !known.contains(code)).toList();
            if (!unknown.isEmpty()) {
                throw AppException.badRequest("Unknown department: " + String.join(", ", unknown), ErrorCode.VALIDATION_ERROR);
            }
            return new Shape(type, new ArrayList<>(codes));
        }
        return new Shape(type, new ArrayList<>());
    }

    // ---------------------------------------------------------------- venues (admin)

    public Venue createVenue(AuthUser actor, Map<String, Object> payload) {
        ClubAccess.assertAdmin(actor);
        Object name = payload.get("name");
        Object location = payload.get("location");
        Object capacity = payload.get("capacity");
        if (isEmpty(name) || isEmpty(location) || isEmpty(capacity) || toNumber(capacity) == 0) {
            throw AppException.badRequest("name, location and capacity are required", ErrorCode.VALIDATION_ERROR);
        }
        Shape shape = normalize(payload.get("type"), payload.get("departmentCodes"), null);
        Venue venue = new Venue();
        venue.setName(String.valueOf(name));
        venue.setLocation(String.valueOf(location));
        venue.setCapacity((int) toNumber(capacity));
        venue.setType(shape.type());
        venue.setDepartmentCodes(shape.departmentCodes());
        return mongo.insert(venue);
    }

    public List<Venue> listVenues(String status, String type, String clubId, String departments) {
        Criteria filter = new Criteria();
        if (status != null && !status.isBlank()) {
            filter = filter.and("status").is(status.toUpperCase(Locale.ROOT));
        }
        if (type != null && !type.isBlank()) {
            filter = filter.and("type").is(type.toUpperCase(Locale.ROOT));
        }
        List<Venue> venues = mongo.find(Query.query(filter).with(Sort.by("type", "name")), Venue.class);
        List<String> audience = audienceFromQuery(clubId, departments);
        return audience == null ? venues : venues.stream().filter(venue -> venueFits(venue, audience)).toList();
    }

    public Venue updateVenue(AuthUser actor, String id, Map<String, Object> data) {
        ClubAccess.assertAdmin(actor);
        Venue existing = mongo.findById(id, Venue.class);
        if (existing == null) {
            throw AppException.notFound("Venue not found");
        }
        if (data.containsKey("name")) {
            existing.setName(String.valueOf(data.get("name")));
        }
        if (data.containsKey("location")) {
            existing.setLocation(String.valueOf(data.get("location")));
        }
        if (data.containsKey("capacity")) {
            existing.setCapacity((int) toNumber(data.get("capacity")));
        }
        if (data.containsKey("status")) {
            try {
                existing.setStatus(VenueStatus.valueOf(String.valueOf(data.get("status")).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw AppException.badRequest("Validation failed: status is invalid", ErrorCode.VALIDATION_ERROR);
            }
        }
        if (data.containsKey("type") || data.containsKey("departmentCodes")) {
            Shape shape = normalize(data.get("type"), data.get("departmentCodes"), existing);
            existing.setType(shape.type());
            existing.setDepartmentCodes(shape.departmentCodes());
        }
        return mongo.save(existing);
    }

    // ---------------------------------------------------------------- conflicts

    /** Something already holding the venue at that time: an event, or an offline interview round. */
    public record Booking(String id, String title, String clubId, String clubName, Instant startAt, Instant endAt, String status) {
    }

    /** Offline interview rounds of live recruitment drives hold their venue for the whole round. */
    List<Booking> findConflictingInterviews(String venueId, Instant startAt, Instant endAt, String excludeRoundId) {
        List<RecruitmentDrive> drives = mongo.find(Query.query(Criteria.where("status").is(RecruitmentStatus.PUBLISHED.name())
                .and("positions.rounds").elemMatch(Criteria.where("venue").is(new ObjectId(venueId)).and("mode").is(RoundMode.OFFLINE.name())
                        .and("status").is(RoundStatus.SCHEDULED.name()).and("startAt").lt(endAt).and("endAt").gt(startAt))), RecruitmentDrive.class);
        Map<String, String> clubNames = clubNames(drives.stream().map(RecruitmentDrive::getClub).toList());
        List<Booking> bookings = new ArrayList<>();
        for (RecruitmentDrive drive : drives) {
            for (RecruitmentPosition position : drive.getPositions()) {
                for (RecruitmentRound round : position.getRounds()) {
                    if (venueId.equals(round.getVenue()) && round.getMode() == RoundMode.OFFLINE && round.getStatus() == RoundStatus.SCHEDULED
                            && round.getStartAt() != null && round.getEndAt() != null
                            && round.getStartAt().isBefore(endAt) && round.getEndAt().isAfter(startAt) && !round.getId().equals(excludeRoundId)) {
                        bookings.add(new Booking(round.getId(), drive.getTitle() + " — " + position.getTitle() + ": " + round.getName(), drive.getClub(), clubNames.get(drive.getClub()),
                                round.getStartAt(), round.getEndAt(), "INTERVIEW"));
                    }
                }
            }
        }
        return bookings;
    }

    public List<Booking> findConflicts(String venueId, Instant startAt, Instant endAt, String excludeEventId, String excludeRoundId) {
        Criteria filter = Criteria.where("venue").is(new ObjectId(venueId))
                .and("status").in(EventStatus.HOLDING_VENUE.stream().map(Enum::name).toList())
                .and("startAt").lt(endAt)
                .and("endAt").gt(startAt);
        if (excludeEventId != null) {
            filter = filter.and("_id").ne(new ObjectId(excludeEventId));
        }
        List<Event> events = mongo.find(Query.query(filter), Event.class);
        Map<String, String> clubNames = clubNames(events.stream().map(Event::getClub).toList());
        List<Booking> conflicts = new ArrayList<>();
        events.forEach(event -> conflicts.add(new Booking(event.getId(), event.getTitle(), event.getClub(), clubNames.get(event.getClub()), event.getStartAt(), event.getEndAt(), event.getStatus().name())));
        conflicts.addAll(findConflictingInterviews(venueId, startAt, endAt, excludeRoundId));
        return conflicts;
    }

    private static String bookingWord(String status) {
        return EventStatus.PENDING_APPROVAL.name().equals(status) ? "requested" : "booked";
    }

    /** 409 VENUE_CONFLICT with every clash in {@code details.conflicts} when the venue is taken. */
    public void assertVenueAvailable(String venueId, Instant startAt, Instant endAt, String excludeEventId, String excludeRoundId) {
        List<Booking> conflicts = findConflicts(venueId, startAt, endAt, excludeEventId, excludeRoundId);
        if (conflicts.isEmpty()) {
            return;
        }
        Booking first = conflicts.get(0);
        Venue venue = mongo.findById(venueId, Venue.class);
        String window = CampusTime.formatTime(first.startAt()) + "–" + CampusTime.formatTime(first.endAt());
        String pendingNote = EventStatus.PENDING_APPROVAL.name().equals(first.status()) ? ", which is awaiting faculty approval" : "";
        List<Map<String, Object>> details = conflicts.stream().map(booking -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", booking.id());
            row.put("title", booking.title());
            row.put("club", booking.clubName());
            row.put("startAt", booking.startAt());
            row.put("endAt", booking.endAt());
            row.put("status", booking.status());
            return row;
        }).toList();
        throw AppException.conflict((venue != null ? venue.getName() : "This venue") + " is already " + bookingWord(first.status()) + " " + window + " for \"" + first.title() + "\""
                        + (first.clubName() != null ? " (" + first.clubName() + ")" : "") + pendingNote + ". Choose another venue or time.", ErrorCode.VENUE_CONFLICT)
                .withDetails(Map.of("conflicts", details));
    }

    /**
     * Serialises "check the venue is free, then claim it" per venue across all replicas. The lock expires on its own
     * if a process dies while holding it.
     */
    public <T> T withVenueLock(String venueId, Supplier<T> task) {
        String key = "cc:lock:venue:" + venueId;
        String token = UUID.randomUUID().toString();
        long giveUpAt = System.currentTimeMillis() + LOCK_WAIT.toMillis();
        while (true) {
            Boolean claimed = redis.opsForValue().setIfAbsent(key, token, LOCK_TTL);
            if (Boolean.TRUE.equals(claimed)) {
                break;
            }
            if (!mongo.exists(Query.query(Criteria.where("_id").is(new ObjectId(venueId))), Venue.class)) {
                throw AppException.notFound("Venue not found");
            }
            if (System.currentTimeMillis() > giveUpAt) {
                throw AppException.conflict("This venue is being booked by someone else right now. Please try again.", ErrorCode.VENUE_CONFLICT);
            }
            try {
                Thread.sleep(40 + ThreadLocalRandom.current().nextInt(60));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        try {
            return task.get();
        } finally {
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException ignored) {
                // The lock expires by itself.
            }
        }
    }

    public void withVenueLock(String venueId, Runnable task) {
        withVenueLock(venueId, () -> {
            task.run();
            return null;
        });
    }

    // ---------------------------------------------------------------- availability (event wizard)

    public List<Map<String, Object>> getAvailableVenues(String eventDate, String endDate, String startTime, String endTime, String excludeEventId, String clubId, String departments) {
        if (isEmpty(eventDate) || isEmpty(startTime) || isEmpty(endTime)) {
            throw AppException.badRequest("eventDate, startTime and endTime are required", ErrorCode.VALIDATION_ERROR);
        }
        Instant startAt = CampusTime.combineDateAndTime(eventDate, startTime);
        Instant endAt = CampusTime.combineDateAndTime(isEmpty(endDate) ? eventDate : endDate, endTime);
        if (!startAt.isBefore(endAt)) {
            throw AppException.badRequest("endTime must be after startTime", ErrorCode.VALIDATION_ERROR);
        }
        List<String> audience = audienceFromQuery(clubId, departments);
        List<Venue> venues = mongo.find(Query.query(Criteria.where("status").is(VenueStatus.ACTIVE.name())).with(Sort.by("type", "name")), Venue.class)
                .stream().filter(venue -> audience == null || venueFits(venue, audience)).toList();

        Criteria busyFilter = Criteria.where("status").in(EventStatus.HOLDING_VENUE.stream().map(Enum::name).toList()).and("startAt").lt(endAt).and("endAt").gt(startAt);
        if (excludeEventId != null && ObjectId.isValid(excludeEventId)) {
            busyFilter = busyFilter.and("_id").ne(new ObjectId(excludeEventId));
        }
        List<Event> busyEvents = mongo.find(Query.query(busyFilter).with(Sort.by("startAt")), Event.class);
        Map<String, String> eventClubs = clubNames(busyEvents.stream().map(Event::getClub).toList());

        record Busy(String venue, String title, String club, Instant startAt, Instant endAt, String status) {
        }
        List<Busy> busy = new ArrayList<>();
        busyEvents.forEach(event -> busy.add(new Busy(event.getVenue(), event.getTitle(), eventClubs.get(event.getClub()), event.getStartAt(), event.getEndAt(), event.getStatus().name())));

        // Interview rounds booked in the same window, from any drive.
        List<RecruitmentDrive> drives = mongo.find(Query.query(Criteria.where("status").is(RecruitmentStatus.PUBLISHED.name())
                .and("positions.rounds").elemMatch(Criteria.where("mode").is(RoundMode.OFFLINE.name()).and("status").is(RoundStatus.SCHEDULED.name())
                        .and("startAt").lt(endAt).and("endAt").gt(startAt))), RecruitmentDrive.class);
        Map<String, String> driveClubs = clubNames(drives.stream().map(RecruitmentDrive::getClub).toList());
        for (RecruitmentDrive drive : drives) {
            drive.getPositions().stream().flatMap(position -> position.getRounds().stream())
                    .filter(round -> round.getVenue() != null && round.getMode() == RoundMode.OFFLINE && round.getStatus() == RoundStatus.SCHEDULED
                            && round.getStartAt() != null && round.getEndAt() != null && round.getStartAt().isBefore(endAt) && round.getEndAt().isAfter(startAt))
                    .forEach(round -> busy.add(new Busy(round.getVenue(), drive.getTitle() + " — " + round.getName(), driveClubs.get(drive.getClub()), round.getStartAt(), round.getEndAt(), "INTERVIEW")));
        }

        // Say who holds each busy venue, so organisers can pick another room or time with confidence.
        Map<String, List<Map<String, Object>>> bookings = new HashMap<>();
        for (Busy entry : busy) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", entry.title());
            row.put("club", entry.club());
            row.put("startTime", CampusTime.formatTime(entry.startAt()));
            row.put("endTime", CampusTime.formatTime(entry.endAt()));
            row.put("pendingApproval", EventStatus.PENDING_APPROVAL.name().equals(entry.status()));
            bookings.computeIfAbsent(entry.venue(), key -> new ArrayList<>()).add(row);
        }
        return venues.stream().map(venue -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> view = json.convertValue(venue, LinkedHashMap.class);
            view.put("available", !bookings.containsKey(venue.getId()));
            view.put("bookedBy", bookings.getOrDefault(venue.getId(), List.of()));
            return view;
        }).toList();
    }

    private Map<String, String> clubNames(List<String> clubIds) {
        List<ObjectId> ids = clubIds.stream().filter(id -> id != null && ObjectId.isValid(id)).distinct().map(ObjectId::new).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(ids));
        query.fields().include("name");
        Map<String, String> names = new HashMap<>();
        mongo.find(query, Document.class, "clubs").forEach(club -> names.put(club.getObjectId("_id").toHexString(), club.getString("name")));
        return names;
    }

    static List<Booking> sortedByStart(List<Booking> bookings) {
        return bookings.stream().sorted(Comparator.comparing(Booking::startAt)).toList();
    }

    private static boolean isEmpty(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static double toNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
