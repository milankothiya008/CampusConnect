package com.campusconnect.feature.checkin;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.tokens.TicketTokens;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.util.Updates;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.CheckInMethod;
import com.campusconnect.domain.event.CheckInStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.event.EventStore;
import com.campusconnect.feature.event.EventViews;
import com.campusconnect.feature.event.Values;
import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Check-in at the door (services/CheckInService.js). The president opens check-in for a published event; while it is
 * open every club officer can scan a ticket's QR code, type its code, or find the student by name or e-mail and mark
 * them present. Marking is one conditional update ({@code checkedInAt: null}), so two officers scanning the same
 * ticket at once check it in exactly once. Attendance counts are always computed from registrations.
 */
@Service
public class CheckInService {

    public static final String CHECKED_IN = "CHECKED_IN";
    public static final String ALREADY_CHECKED_IN = "ALREADY_CHECKED_IN";
    public static final String INVALID_TICKET = "INVALID_TICKET";
    public static final String NOT_REGISTERED = "NOT_REGISTERED";
    public static final String WRONG_EVENT = "WRONG_EVENT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNMARKED = "UNMARKED";

    private final MongoOperations mongo;
    private final EventStore events;
    private final EventViews views;
    private final ClubAccess access;
    private final NotificationService notifications;
    private final AuditService audit;
    private final TicketTokens tokens;
    private final Refs refs;

    public CheckInService(MongoOperations mongo, EventStore events, EventViews views, ClubAccess access, NotificationService notifications, AuditService audit,
            TicketTokens tokens, Refs refs) {
        this.mongo = mongo;
        this.events = events;
        this.views = views;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.tokens = tokens;
        this.refs = refs;
    }

    private static Criteria registered(String eventId) {
        return Criteria.where("event").is(new ObjectId(eventId)).and("status").is(RegistrationStatus.REGISTERED.name());
    }

    /** Registered and attended counts, always computed from registrations so undoing a check-in can't drift. */
    /** People checked in at the door, per event id (for the club insights). */
    public Map<String, Integer> attendedCountsByEvent(Collection<ObjectId> eventIds) {
        Map<String, Integer> counts = new HashMap<>();
        if (eventIds.isEmpty()) {
            return counts;
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("event").in(eventIds).and("status").is(RegistrationStatus.REGISTERED.name()).and("checkedInAt").ne(null)),
                Aggregation.group("event").count().as("attended"));
        for (Document row : mongo.aggregate(aggregation, "eventregistrations", Document.class)) {
            counts.put(row.getObjectId("_id").toHexString(), ((Number) row.get("attended")).intValue());
        }
        return counts;
    }

    public Map<String, Object> counts(String eventId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("registered", mongo.count(Query.query(registered(eventId)), EventRegistration.class));
        counts.put("attended", mongo.count(Query.query(registered(eventId).and("checkedInAt").ne(null)), EventRegistration.class));
        return counts;
    }

    /** Attendee rows with their user, team and checker populated, one query per collection. */
    private List<Map<String, Object>> attendeeViews(List<EventRegistration> registrations, boolean skipMissingUsers) {
        Map<String, Map<String, Object>> users = refs.users(registrations.stream().map(EventRegistration::getUser).toList(), "name", "email", "departmentCode", "batchCode");
        Map<String, Map<String, Object>> teams = refs.load("teams", registrations.stream().map(EventRegistration::getTeam).filter(Objects::nonNull).toList(), "name");
        Map<String, Map<String, Object>> checkers = refs.users(registrations.stream().map(EventRegistration::getCheckedInBy).filter(Objects::nonNull).toList(), "name");
        List<Map<String, Object>> views = new ArrayList<>();
        for (EventRegistration registration : registrations) {
            Map<String, Object> user = users.get(registration.getUser());
            if (user == null && skipMissingUsers) {
                continue;
            }
            Map<String, Object> team = registration.getTeam() == null ? null : teams.get(registration.getTeam());
            Map<String, Object> checker = registration.getCheckedInBy() == null ? null : checkers.get(registration.getCheckedInBy());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("registrationId", registration.getId());
            view.put("name", user != null && user.get("name") != null ? user.get("name") : "Former student");
            view.put("email", user == null ? null : user.get("email"));
            view.put("departmentCode", user == null ? null : user.get("departmentCode"));
            view.put("batchCode", user == null ? null : user.get("batchCode"));
            view.put("team", team == null ? null : team.get("name"));
            view.put("teamRole", registration.getTeamRole());
            view.put("ticketCode", registration.getTicketCode());
            view.put("checkedInAt", registration.getCheckedInAt());
            view.put("checkInMethod", registration.getCheckInMethod());
            view.put("checkedInBy", checker == null ? null : checker.get("name"));
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> attendeeView(EventRegistration registration) {
        return attendeeViews(List.of(registration), false).get(0);
    }

    private String studentName(EventRegistration registration) {
        Map<String, Object> user = refs.user(registration.getUser(), "name");
        return user != null && user.get("name") != null ? String.valueOf(user.get("name")) : "Student";
    }

    private static void assertOpen(Event event) {
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw AppException.conflict("Check-in is only available for published events", ErrorCode.INVALID_STATE);
        }
        if (event.getCheckIn() == null || event.getCheckIn().getStatus() != CheckInStatus.OPEN) {
            throw AppException.conflict("Check-in is not open. The club president can open it from the event page.", ErrorCode.INVALID_STATE);
        }
    }

    private void checkInAudit(Event event, AuthUser actor, AuditAction action, String targetId, Map<String, Object> metadata) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", event.getId());
        details.put("clubId", event.getClub());
        details.putAll(metadata);
        audit.record(action, actor.id(), "Event", targetId, details);
    }

    // ---------------------------------------------------------------- opening and closing (president)

    public Map<String, Object> openCheckIn(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_CHECK_IN, "Only the club president can open check-in");
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw AppException.conflict("Check-in can only be opened for published events", ErrorCode.INVALID_STATE);
        }
        boolean reopened = event.getCheckIn() != null && event.getCheckIn().getStatus() == CheckInStatus.CLOSED;
        boolean opened = events.update(
                Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("status").is(EventStatus.PUBLISHED.name()).and("checkIn.status").ne(CheckInStatus.OPEN.name())),
                new Update().set("checkIn.status", CheckInStatus.OPEN.name()).set("checkIn.openedAt", Instant.now()).set("checkIn.openedBy", new ObjectId(actor.id()))
                        .set("checkIn.closedAt", null).set("checkIn.closedBy", null));
        if (!opened) {
            throw AppException.conflict("Check-in is already open", ErrorCode.INVALID_STATE);
        }
        checkInAudit(event, actor, AuditAction.CHECK_IN_OPENED, event.getId(), Map.of("reopened", reopened));
        notifications.notify(access.usersWithPermission(context.club().getId(), ClubPermission.MARK_ATTENDANCE), Notice.builder()
                .type(NotificationType.CHECK_IN_OPEN)
                .title("Check-in is open for " + event.getTitle())
                .message("Scan tickets at the entrance, or search students by name or email to mark them present.")
                .link("/events/" + event.getId() + "/check-in")
                .exclude(List.of(actor.id()))
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> closeCheckIn(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_CHECK_IN, "Only the club president can close check-in");
        boolean closed = events.update(
                Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("checkIn.status").is(CheckInStatus.OPEN.name())),
                new Update().set("checkIn.status", CheckInStatus.CLOSED.name()).set("checkIn.closedAt", Instant.now()).set("checkIn.closedBy", new ObjectId(actor.id())));
        if (!closed) {
            throw AppException.conflict("Check-in is not open", ErrorCode.INVALID_STATE);
        }
        checkInAudit(event, actor, AuditAction.CHECK_IN_CLOSED, event.getId(), Map.of());
        return views.getEventDetail(actor, event.getId());
    }

    // ---------------------------------------------------------------- what the scanner page shows

    public Map<String, Object> getCheckInStatus(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.MARK_ATTENDANCE, "Only club officers can run check-in");
        List<EventRegistration> recent = mongo.find(Query.query(registered(event.getId()).and("checkedInAt").ne(null))
                .with(Sort.by(Sort.Direction.DESC, "checkedInAt")).limit(10), EventRegistration.class);

        Map<String, Object> eventView = new LinkedHashMap<>();
        eventView.put("_id", event.getId());
        eventView.put("title", event.getTitle());
        eventView.put("status", event.getStatus());
        eventView.put("startAt", event.getStartAt());
        eventView.put("endAt", event.getEndAt());
        eventView.put("participationMode", event.getParticipationMode());
        eventView.put("venue", event.getVenue());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("event", eventView);
        status.put("checkIn", event.getCheckIn());
        status.put("counts", counts(event.getId()));
        status.put("recent", attendeeViews(recent, false));
        status.put("canManage", context.has(ClubPermission.MANAGE_CHECK_IN));
        return status;
    }

    /**
     * Registered students for the search tab. Deliberately a limited view (no registration management), so every
     * officer can check people in even without the participants permission.
     */
    public List<Map<String, Object>> listForCheckIn(AuthUser actor, String eventId, String search) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MARK_ATTENDANCE, "Only club officers can run check-in");
        Criteria filter = registered(event.getId());
        String text = search == null ? "" : search.strip();
        if (!text.isEmpty()) {
            Query users = Query.query(new Criteria().orOperator(Texts.search("name", text), Texts.search("email", text)));
            users.fields().include("_id");
            filter = filter.and("user").in(mongo.find(users, User.class).stream().map(user -> new ObjectId(user.getId())).toList());
        }
        List<EventRegistration> rows = mongo.find(Query.query(filter).limit(300), EventRegistration.class);
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        List<Map<String, Object>> attendees = new ArrayList<>(attendeeViews(rows, true));
        attendees.sort(Comparator.comparing(row -> String.valueOf(row.get("name")), collator));
        return attendees;
    }

    // ---------------------------------------------------------------- marking attendance

    /** A ticket lookup: either the registration to check in, or the outcome to report instead. */
    private record Resolved(String result, String message, EventRegistration registration) {

        static Resolved ok(EventRegistration registration) {
            return new Resolved(null, null, registration);
        }
    }

    private static Resolved notRegistered(EventRegistration registration) {
        return new Resolved(NOT_REGISTERED, registration.getStatus() == RegistrationStatus.WAITLISTED
                ? "This student is on the waitlist, not registered" : "Registration was cancelled — ticket no longer valid", registration);
    }

    private Resolved wrongEvent(EventRegistration registration) {
        Map<String, Object> other = refs.event(registration.getEvent(), "title");
        Object title = other == null ? null : other.get("title");
        return new Resolved(WRONG_EVENT, "This ticket is for \"" + (title == null ? "another event" : title) + "\"", registration);
    }

    /** Finds the registration a scan, code or manual pick refers to, or explains why it can't be used. */
    private Resolved resolve(Event event, String token, String code, String registrationId) {
        EventRegistration registration;
        if (token != null && !token.isEmpty()) {
            TicketTokens.Ticket ticket = tokens.read(token);
            if (ticket == null) {
                return new Resolved(INVALID_TICKET, "This QR code is not a valid ticket", null);
            }
            registration = mongo.findById(ticket.registrationId(), EventRegistration.class);
            if (registration == null) {
                return new Resolved(INVALID_TICKET, "This QR code is not a valid ticket", null);
            }
            if (!event.getId().equals(registration.getEvent())) {
                return wrongEvent(registration);
            }
            if (!ticket.ticketCode().equals(registration.getTicketCode())) {
                return new Resolved(INVALID_TICKET, "This ticket was replaced — ask the student to open their current ticket", registration);
            }
        } else if (code != null) {
            String normalized = TicketTokens.normalizeCode(code);
            if (normalized == null) {
                return new Resolved(NOT_FOUND, "Enter a ticket code like CC-7K3M9QWA", null);
            }
            registration = mongo.findOne(Query.query(Criteria.where("ticketCode").is(normalized)), EventRegistration.class);
            if (registration == null) {
                return new Resolved(NOT_FOUND, "No ticket with code " + normalized, null);
            }
            if (!event.getId().equals(registration.getEvent())) {
                return wrongEvent(registration);
            }
        } else {
            registration = registrationId == null || !ObjectId.isValid(registrationId) ? null
                    : mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(registrationId)).and("event").is(new ObjectId(event.getId()))), EventRegistration.class);
            if (registration == null) {
                return new Resolved(NOT_FOUND, "Registration not found", null);
            }
        }
        if (registration.getStatus() != RegistrationStatus.REGISTERED) {
            return notRegistered(registration);
        }
        return Resolved.ok(registration);
    }

    private Map<String, Object> outcome(Event event, String result, String message, EventRegistration registration) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("result", result);
        outcome.put("message", message);
        outcome.put("attendee", registration == null ? null : attendeeView(registration));
        outcome.put("counts", counts(event.getId()));
        return outcome;
    }

    /**
     * Marks a student present from a scanned token, a typed ticket code, or a registration picked from the search
     * list. Every outcome is a normal 200 with {@code result}; only permission and "check-in not open" are errors.
     */
    public Map<String, Object> markAttendance(AuthUser actor, String eventId, String token, String code, String registrationId, String note, CheckInMethod method) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MARK_ATTENDANCE, "Only club officers can mark attendance");
        assertOpen(event);

        Resolved resolved = resolve(event, token, code, registrationId);
        if (resolved.result() != null) {
            return outcome(event, resolved.result(), resolved.message(), resolved.registration());
        }
        Instant now = Instant.now();
        String cleanNote = Values.truthy(note) ? Values.clean(note, 200) : null;
        EventRegistration updated = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(resolved.registration().getId())).and("event").is(new ObjectId(event.getId()))
                        .and("status").is(RegistrationStatus.REGISTERED.name()).and("checkedInAt").is(null)),
                Updates.touch(new Update().set("checkedInAt", now).set("checkedInBy", new ObjectId(actor.id())).set("checkInMethod", method.name()).set("checkInNote", cleanNote)),
                FindAndModifyOptions.options().returnNew(true), EventRegistration.class);

        if (updated == null) {
            // Someone else scanned it a moment ago, or it was cancelled in the meantime.
            EventRegistration current = mongo.findById(resolved.registration().getId(), EventRegistration.class);
            if (current != null && current.getCheckedInAt() != null) {
                Map<String, Object> checker = current.getCheckedInBy() == null ? null : refs.user(current.getCheckedInBy(), "name");
                return outcome(event, ALREADY_CHECKED_IN, "Already checked in at " + CampusTime.formatTime(current.getCheckedInAt())
                        + (checker != null ? " by " + checker.get("name") : ""), current);
            }
            Resolved refused = notRegistered(current != null ? current : resolved.registration());
            return outcome(event, refused.result(), refused.message(), refused.registration());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", updated.getUser());
        metadata.put("method", method.name());
        checkInAudit(event, actor, AuditAction.ATTENDANCE_MARKED, updated.getId(), metadata);
        notifications.notify(updated.getUser(), Notice.builder()
                .type(NotificationType.ATTENDANCE_MARKED)
                .title("You're checked in to " + event.getTitle())
                .message("Welcome! You were marked present at " + CampusTime.formatTime(now) + ".")
                .link("/events/" + event.getId())
                .build());
        return outcome(event, CHECKED_IN, studentName(updated) + " checked in", updated);
    }

    public Map<String, Object> unmarkAttendance(AuthUser actor, String eventId, String registrationId) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MARK_ATTENDANCE, "Only club officers can change attendance");
        assertOpen(event);
        EventRegistration updated = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(registrationId)).and("event").is(new ObjectId(event.getId())).and("checkedInAt").ne(null)),
                Updates.touch(new Update().set("checkedInAt", null).set("checkedInBy", null).set("checkInMethod", null).set("checkInNote", null)),
                FindAndModifyOptions.options().returnNew(true), EventRegistration.class);
        if (updated == null) {
            throw AppException.conflict("This student isn't checked in", ErrorCode.INVALID_STATE);
        }
        checkInAudit(event, actor, AuditAction.ATTENDANCE_UNMARKED, updated.getId(), Map.of("userId", updated.getUser()));
        return outcome(event, UNMARKED, studentName(updated) + "'s check-in was undone", updated);
    }
}
