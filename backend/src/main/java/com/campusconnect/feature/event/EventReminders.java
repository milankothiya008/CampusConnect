package com.campusconnect.feature.event;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.mail.EmailContent;
import com.campusconnect.core.mail.EmailContent.Detail;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.EmailRequest;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.SentReminder;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.feature.audience.CampusMailer;
import com.campusconnect.feature.subscription.SubscriptionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Event reminders (services/EventReminderService.js). Automatic ones go to registered students 24 hours and 1 hour
 * before the start; each is claimed atomically on the event ({@code remindersSent}) so it goes out exactly once, even
 * with several replicas. Officers with SEND_REMINDERS can also send one by hand: "registration is closing" to eligible
 * students who haven't registered, or "starting soon" to everyone registered — at most once every few hours each.
 */
@Slf4j
@Service
public class EventReminders {

    static final Duration MANUAL_COOLDOWN = Duration.ofHours(6);
    static final Map<String, String> MANUAL = Map.of("REGISTRATION_CLOSING", "MANUAL_REG_CLOSING", "EVENT_STARTING", "MANUAL_STARTING");

    /** 24 hours before (sent when 23–24 hours are left) and 1 hour before (when 15–75 minutes are left). */
    record Rule(String kind, Duration from, Duration to, String lead) {
    }

    static final List<Rule> AUTOMATIC = List.of(
            new Rule("START_24H", Duration.ofHours(23), Duration.ofHours(24), "Tomorrow"),
            new Rule("START_1H", Duration.ofMinutes(15), Duration.ofMinutes(75), "Starting soon"));

    private final MongoOperations mongo;
    private final EventStore events;
    private final ClubAccess access;
    private final NotificationService notifications;
    private final AuditService audit;
    private final CampusMailer mailer;
    private final SubscriptionService subscriptions;
    private final PausedClubs pausedClubs;

    public EventReminders(MongoOperations mongo, EventStore events, ClubAccess access, NotificationService notifications, AuditService audit, CampusMailer mailer,
            SubscriptionService subscriptions, PausedClubs pausedClubs) {
        this.mongo = mongo;
        this.events = events;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.mailer = mailer;
        this.subscriptions = subscriptions;
        this.pausedClubs = pausedClubs;
    }

    static String link(Event event) {
        return "/events/" + event.getId();
    }

    /** "45 minutes", "3 hours", "2 days". */
    public static String timeUntil(Instant date, Instant now) {
        long minutes = Math.max(1, Math.round((date.toEpochMilli() - now.toEpochMilli()) / 60_000.0));
        if (minutes < 60) {
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        }
        long hours = Math.round(minutes / 60.0);
        if (hours < 48) {
            return hours + " hour" + (hours == 1 ? "" : "s");
        }
        return Math.round(hours / 24.0) + " days";
    }

    private List<String> userIds(String eventId, List<String> statuses) {
        Query query = Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").in(statuses));
        query.fields().include("user");
        return mongo.find(query, EventRegistration.class).stream().map(EventRegistration::getUser).toList();
    }

    /** Claims a reminder kind on the event; false when it was already sent. */
    public boolean claim(Event event, String kind) {
        Document reminder = new Document("kind", kind).append("at", new java.util.Date()).append("by", null).append("recipients", 0);
        return events.update(Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("remindersSent.kind").ne(kind)),
                new Update().push("remindersSent", reminder));
    }

    private void recordRecipients(Event event, String kind, int recipients) {
        events.update(Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("remindersSent.kind").is(kind)),
                new Update().set("remindersSent.$.recipients", recipients));
    }

    /** "Starting soon" to everyone registered: in-app (and push) plus an e-mail they can mute under "Your events". */
    int sendStartingReminder(Event event, String lead, String note) {
        List<String> users = userIds(event.getId(), List.of(RegistrationStatus.REGISTERED.name()));
        if (users.isEmpty()) {
            return 0;
        }
        Venue venue = event.getVenue() == null ? null : mongo.findById(event.getVenue(), Venue.class);
        String where = venue != null ? venue.getName() + ", " + venue.getLocation() : null;
        String title = lead + ": " + event.getTitle();
        String schedule = CampusTime.formatSchedule(event.getStartAt(), event.getEndAt());
        notifications.notify(users, Notice.builder()
                .type(NotificationType.EVENT_REMINDER)
                .title(title)
                .message(schedule + (where != null ? " · " + where : "") + ". Bring your ticket from the event page.")
                .link(link(event))
                .build());
        long sentAt = System.currentTimeMillis();
        String heading = event.getTitle() + " starts in " + timeUntil(event.getStartAt(), Instant.now());
        notifications.emailUsers(users, EmailRequest.builder()
                .category(EmailCategory.EVENT_ACTIVITY)
                .dedupeKey(user -> "reminder:" + event.getId() + ":" + lead + ":" + sentAt + ":" + user.id())
                .compose(user -> EmailContent.builder()
                        .subject(title)
                        .heading(heading)
                        .paragraphs(List.of(note != null && !note.isEmpty() ? note : "See you there! Your QR ticket is on the event page — show it at the door to check in."))
                        .details(List.of(new Detail("When", schedule), new Detail("Where", where)))
                        .action(new EmailContent.Action("Open your ticket", link(event)))
                        .reason("You're receiving this because you registered for this event.")
                        .build())
                .build());
        return users.size();
    }

    /** "Registration closing" to eligible students who haven't registered (and didn't mute the club). */
    int sendClosingReminder(Event event, String note) {
        Set<String> skip = new HashSet<>(userIds(event.getId(), List.of(RegistrationStatus.REGISTERED.name(), RegistrationStatus.WAITLISTED.name())));
        skip.addAll(subscriptions.optedOutIds(event.getClub()));
        List<String> users = mailer.eligibleStudentIds(event).stream().filter(id -> !skip.contains(id)).toList();
        if (users.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        String title = "Registration closes in " + timeUntil(event.getRegistrationEnd(), now) + ": " + event.getTitle();
        String closes = CampusTime.formatDate(event.getRegistrationEnd()) + ", " + CampusTime.formatTime(event.getRegistrationEnd());
        notifications.notify(users, Notice.builder()
                .type(NotificationType.EVENT_REMINDER)
                .title(title)
                .message("Register by " + closes + ".")
                .link(link(event))
                .build());
        long sentAt = System.currentTimeMillis();
        String paragraph = note != null && !note.isEmpty() ? note : event.getShortDescription();
        Integer max = event.getMaxParticipants();
        String seats = max != null && max > 0 ? Math.max(0, max - event.getRegisteredCount()) + " of " + max + " left" : null;
        notifications.emailUsers(users, EmailRequest.builder()
                .category(EmailCategory.EVENT_RECOMMENDATIONS)
                .dedupeKey(user -> "reminder:" + event.getId() + ":closing:" + sentAt + ":" + user.id())
                .compose(user -> EmailContent.builder()
                        .subject(title)
                        .heading("Last chance to join " + event.getTitle())
                        .image(event.getPoster())
                        .paragraphs(paragraph == null || paragraph.isEmpty() ? List.of() : List.of(paragraph))
                        .details(List.of(
                                new Detail("When", CampusTime.formatSchedule(event.getStartAt(), event.getEndAt())),
                                new Detail("Register by", closes),
                                new Detail("Seats", seats)))
                        .action(new EmailContent.Action("Register now", link(event)))
                        .reason("You're receiving this because this event is open to your department and batch.")
                        .build())
                .build());
        return users.size();
    }

    private static SentReminder lastManual(Event event, String kind) {
        return event.getRemindersSent().stream().filter(item -> kind.equals(item.getKind()))
                .max(Comparator.comparing(SentReminder::getAt, Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
    }

    /** What the reminder button shows: which reminders can go out now, and when the last ones went. */
    public Map<String, Object> reminderStatus(Event event) {
        Instant now = Instant.now();
        boolean published = event.getStatus() == EventStatus.PUBLISHED;
        boolean closingOpen = published && !event.isRegistrationClosed() && event.getRegistrationEnd() != null && event.getRegistrationEnd().isAfter(now)
                && event.getStartAt().isAfter(now);
        boolean startingOpen = published && event.getEndAt().isAfter(now);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("REGISTRATION_CLOSING", manualStatus(event, MANUAL.get("REGISTRATION_CLOSING"), closingOpen, "Registration isn't open right now", now));
        status.put("EVENT_STARTING", manualStatus(event, MANUAL.get("EVENT_STARTING"), startingOpen, "The event has ended", now));
        status.put("automatic", event.getRemindersSent().stream().filter(item -> item.getKind() != null && !item.getKind().startsWith("MANUAL")).map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", item.getKind());
            row.put("at", item.getAt());
            row.put("recipients", item.getRecipients());
            return row;
        }).toList());
        return status;
    }

    private static Map<String, Object> manualStatus(Event event, String kind, boolean available, String reason, Instant now) {
        SentReminder last = lastManual(event, kind);
        Instant nextAt = last == null || last.getAt() == null ? null : last.getAt().plus(MANUAL_COOLDOWN);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("available", available && (nextAt == null || !nextAt.isAfter(now)));
        state.put("reason", !available ? reason
                : nextAt != null && nextAt.isAfter(now) ? "Sent " + timeUntil(now, last.getAt()) + " ago — you can send another after " + CampusTime.formatTime(nextAt)
                : null);
        state.put("lastSentAt", last == null ? null : last.getAt());
        state.put("lastRecipients", last == null ? null : last.getRecipients());
        return state;
    }

    /** An officer with SEND_REMINDERS sends a reminder now. */
    public Map<String, Object> sendManualReminder(AuthUser actor, String eventId, String kind, String note) {
        Event event = events.find(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.SEND_REMINDERS, "You don't have the authority to send reminders for this club");
        if (context.club().getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict(context.club().getName() + " is " + context.club().getStatus().name().toLowerCase(Locale.ROOT) + ", so reminders are on hold",
                    ErrorCode.CLUB_NOT_ACTIVE);
        }
        String key = MANUAL.get(kind);
        if (key == null) {
            throw AppException.badRequest("Choose which reminder to send", ErrorCode.VALIDATION_ERROR);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) reminderStatus(event).get(kind);
        if (!Boolean.TRUE.equals(state.get("available"))) {
            throw AppException.conflict(state.get("reason") != null ? String.valueOf(state.get("reason")) : "This reminder can't be sent right now", ErrorCode.INVALID_STATE);
        }
        String text = Values.clean(note, 500);

        // Claim the cooldown slot first, so two officers pressing the button together send one reminder.
        Instant since = Instant.now().minus(MANUAL_COOLDOWN);
        Document reminder = new Document("kind", key).append("at", new java.util.Date()).append("by", new ObjectId(actor.id())).append("recipients", 0);
        boolean claimed = events.update(
                Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("remindersSent").not().elemMatch(Criteria.where("kind").is(key).and("at").gt(since))),
                new Update().push("remindersSent", reminder));
        if (!claimed) {
            throw AppException.conflict("A reminder like this was just sent", ErrorCode.INVALID_STATE);
        }

        int recipients = "EVENT_STARTING".equals(kind)
                ? sendStartingReminder(event, event.getStartAt().isAfter(Instant.now()) ? "Starts in " + timeUntil(event.getStartAt(), Instant.now()) : "Happening now", text)
                : sendClosingReminder(event, text);

        events.update(Query.query(Criteria.where("_id").is(new ObjectId(event.getId()))), new Update()
                .set("remindersSent.$[last].recipients", recipients)
                .filterArray(Criteria.where("last.kind").is(key).and("last.by").is(new ObjectId(actor.id())).and("last.recipients").is(0)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", event.getClub());
        metadata.put("kind", kind);
        metadata.put("recipients", recipients);
        audit.record(AuditAction.EVENT_REMINDER_SENT, actor.id(), "Event", event.getId(), metadata);
        log.info("Manual event reminder for {}: {} to {} people", event.getId(), kind, recipients);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("recipients", recipients);
        result.put("status", reminderStatus(events.find(event.getId())));
        return result;
    }

    // ---------------------------------------------------------------- automatic sweep

    /** Every minute; one replica at a time (the claims make a second run harmless anyway). */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    @SchedulerLock(name = "eventReminders", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
    public void scheduledSweep() {
        try {
            int sent = sweepEventReminders();
            if (sent > 0) {
                log.info("Event reminder sweep: {} reminders sent", sent);
            }
        } catch (RuntimeException e) {
            log.error("Event reminder sweep failed: {}", e.getMessage());
        }
    }

    /** Sends the automatic reminders that are due now; returns how many events were reminded. */
    public int sweepEventReminders() {
        Instant now = Instant.now();
        List<ObjectId> paused = pausedClubs.ids().stream().map(ObjectId::new).toList();
        int sent = 0;
        for (Rule rule : AUTOMATIC) {
            Criteria criteria = Criteria.where("status").is(EventStatus.PUBLISHED.name())
                    .and("startAt").gt(now.plus(rule.from())).lte(now.plus(rule.to()))
                    .and("remindersSent.kind").ne(rule.kind());
            if (!paused.isEmpty()) {
                criteria = criteria.and("club").nin(paused);
            }
            for (Event event : mongo.find(Query.query(criteria), Event.class)) {
                if (!claim(event, rule.kind())) {
                    continue;
                }
                int recipients = sendStartingReminder(event, rule.lead(), "");
                recordRecipients(event, rule.kind(), recipients);
                sent++;
            }
        }
        return sent;
    }
}
