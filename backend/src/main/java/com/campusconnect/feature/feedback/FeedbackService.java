package com.campusconnect.feature.feedback;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.event.CheckInStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventFeedback;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.feature.event.EventReminders;
import com.campusconnect.feature.event.EventStore;
import com.campusconnect.feature.event.Values;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Feedback after an event (services/FeedbackService.js): the students who were there rate it (1–5 stars) and can
 * leave a note. They have two weeks and can change their answer until then. Organisers and the mentor see the
 * average, the spread and the notes — without names.
 */
@Slf4j
@Service
public class FeedbackService {

    static final Duration WINDOW = Duration.ofDays(14);
    static final List<EventStatus> FEEDBACK_STATUSES = EventStatus.PUBLIC;

    private final MongoOperations mongo;
    private final EventStore events;
    private final ClubAccess access;
    private final NotificationService notifications;
    private final EventReminders reminders;

    public FeedbackService(MongoOperations mongo, EventStore events, ClubAccess access, NotificationService notifications, EventReminders reminders) {
        this.mongo = mongo;
        this.events = events;
        this.access = access;
        this.notifications = notifications;
        this.reminders = reminders;
    }

    static Instant closesAt(Event event) {
        return event.getEndAt().plus(WINDOW);
    }

    /** Who was there: checked in — or, if the club never used check-in, everyone registered. */
    static Criteria attendeeFilter(Event event) {
        Criteria filter = Criteria.where("event").is(new ObjectId(event.getId())).and("status").is(RegistrationStatus.REGISTERED.name());
        if (event.getCheckIn() != null && event.getCheckIn().getStatus() != null && event.getCheckIn().getStatus() != CheckInStatus.NOT_STARTED) {
            filter = filter.and("checkedInAt").ne(null);
        }
        return filter;
    }

    private boolean isAttendee(Event event, String userId) {
        return mongo.exists(Query.query(attendeeFilter(event).and("user").is(new ObjectId(userId))), EventRegistration.class);
    }

    /** UNAVAILABLE, NOT_YET, OPEN or CLOSED. */
    static String windowState(Event event, Instant now) {
        if (!FEEDBACK_STATUSES.contains(event.getStatus())) {
            return "UNAVAILABLE";
        }
        if (event.getEndAt().isAfter(now)) {
            return "NOT_YET";
        }
        if (closesAt(event).isBefore(now)) {
            return "CLOSED";
        }
        return "OPEN";
    }

    /** The feedback card on the event page: the participant's own answer, and for organisers the summary. */
    public Map<String, Object> getFeedback(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        ClubContext context = access.context(actor, event.getClub());
        boolean organiser = context.isMentor() || context.has(ClubPermission.MANAGE_EVENTS) || context.has(ClubPermission.VIEW_PARTICIPANTS);
        String state = windowState(event, Instant.now());
        EventFeedback mine = mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").is(new ObjectId(actor.id()))), EventFeedback.class);
        boolean attendee = isAttendee(event, actor.id());

        Map<String, Object> summary = null;
        if (organiser) {
            Query rowsQuery = Query.query(Criteria.where("event").is(new ObjectId(event.getId()))).with(Sort.by(Sort.Direction.DESC, "updatedAt"));
            rowsQuery.fields().include("rating", "note", "updatedAt");
            List<EventFeedback> rows = mongo.find(rowsQuery, EventFeedback.class);
            summary = new LinkedHashMap<>(summaryOf(rows));
            summary.put("attendees", mongo.count(Query.query(attendeeFilter(event)), EventRegistration.class));
            summary.put("notes", rows.stream().filter(row -> row.getNote() != null && !row.getNote().isEmpty()).map(row -> {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("rating", row.getRating());
                note.put("note", row.getNote());
                note.put("at", row.getUpdatedAt());
                return note;
            }).toList());
        }

        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("state", state);
        feedback.put("closesAt", closesAt(event));
        feedback.put("canGive", attendee && "OPEN".equals(state));
        if (mine == null) {
            feedback.put("mine", null);
        } else {
            Map<String, Object> own = new LinkedHashMap<>();
            own.put("rating", mine.getRating());
            own.put("note", mine.getNote());
            own.put("updatedAt", mine.getUpdatedAt());
            feedback.put("mine", own);
        }
        feedback.put("summary", summary);
        return feedback;
    }

    static Map<String, Object> summaryOf(List<EventFeedback> rows) {
        List<Integer> distribution = new ArrayList<>();
        for (int stars = 1; stars <= 5; stars++) {
            int value = stars;
            distribution.add((int) rows.stream().filter(row -> row.getRating() == value).count());
        }
        Number average = rows.isEmpty() ? null
                : Values.jsNumber(Math.round(rows.stream().mapToInt(EventFeedback::getRating).sum() / (double) rows.size() * 10) / 10.0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", rows.size());
        summary.put("average", average);
        summary.put("distribution", distribution);
        return summary;
    }

    public Map<String, Object> giveFeedback(AuthUser actor, String eventId, Object rating, String note) {
        Event event = events.find(eventId);
        String state = windowState(event, Instant.now());
        if ("NOT_YET".equals(state)) {
            throw AppException.conflict("You can rate the event once it has ended", ErrorCode.INVALID_STATE);
        }
        if (!"OPEN".equals(state)) {
            throw AppException.conflict("Feedback for this event is closed", ErrorCode.INVALID_STATE);
        }
        if (!isAttendee(event, actor.id())) {
            throw AppException.forbidden("Only students who attended can rate this event", ErrorCode.FORBIDDEN);
        }
        double stars = Values.number(rating);
        if (!Values.isInteger(stars) || stars < 1 || stars > 5) {
            throw AppException.badRequest("Choose 1 to 5 stars", ErrorCode.VALIDATION_ERROR);
        }
        Instant now = Instant.now();
        mongo.upsert(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").is(new ObjectId(actor.id()))),
                new Update().set("rating", (int) stars).set("note", Values.clean(Values.truthy(note) ? note : "", 1000)).set("club", new ObjectId(event.getClub()))
                        .set("updatedAt", now).setOnInsert("createdAt", now),
                EventFeedback.class);
        return getFeedback(actor, event.getId());
    }

    // ---------------------------------------------------------------- the request after the event

    /** Every few minutes; one replica at a time (each event is claimed, so a second run sends nothing). */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT45S")
    @SchedulerLock(name = "feedbackRequests", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
    public void scheduledSweep() {
        try {
            int sent = sweepFeedbackRequests();
            if (sent > 0) {
                log.info("Feedback sweep: {} events asked for feedback", sent);
            }
        } catch (RuntimeException e) {
            log.error("Feedback sweep failed: {}", e.getMessage());
        }
    }

    /** Once an event ends, its attendees are asked (once) how it was. Returns how many events were asked about. */
    public int sweepFeedbackRequests() {
        Instant now = Instant.now();
        List<Event> ended = mongo.find(Query.query(Criteria.where("status").in(FEEDBACK_STATUSES.stream().map(Enum::name).toList())
                .and("endAt").lte(now).gt(now.minus(Duration.ofDays(2)))
                .and("remindersSent.kind").ne("FEEDBACK")), Event.class);
        int sent = 0;
        for (Event event : ended) {
            if (!reminders.claim(event, "FEEDBACK")) {
                continue;
            }
            Query attendees = Query.query(attendeeFilter(event));
            attendees.fields().include("user");
            notifications.notify(mongo.find(attendees, EventRegistration.class).stream().map(EventRegistration::getUser).toList(), Notice.builder()
                    .type(NotificationType.FEEDBACK_REQUEST)
                    .title("How was " + event.getTitle() + "?")
                    .message("Rate it and tell the club what to keep or change — your name isn't shown to them.")
                    .link("/events/" + event.getId() + "#feedback")
                    .build());
            sent++;
        }
        return sent;
    }
}
