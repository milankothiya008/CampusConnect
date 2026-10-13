package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.Values.truthy;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.events.EventCertificatesDue;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.CheckInStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.social.FeedPostType;
import com.campusconnect.feature.audience.AudienceService;
import com.campusconnect.feature.audience.CampusMailer;
import com.campusconnect.feature.feed.SystemPosts;
import com.campusconnect.feature.subscription.SubscriptionService;
import com.campusconnect.feature.venue.VenueService;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * An event's journey after it is drafted (EventService.js): DRAFT → PENDING_APPROVAL (the faculty mentor reviews) →
 * APPROVED or NEEDS_CHANGES or REJECTED → PUBLISHED (open for registration) → COMPLETED, or CANCELLED on the way.
 * Each step is a conditional update on the event's current status, so two officers clicking at once cannot apply a
 * step twice; steps that reserve the venue run under the venue's lock.
 */
@Slf4j
@Service
public class EventLifecycle {

    private final EventStore events;
    private final EventViews views;
    private final ClubAccess access;
    private final VenueService venues;
    private final EventNews news;
    private final NotificationService notifications;
    private final AudienceService audiences;
    private final CampusMailer mailer;
    private final SubscriptionService subscriptions;
    private final SystemPosts systemPosts;
    private final ApplicationEventPublisher publisher;

    public EventLifecycle(EventStore events, EventViews views, ClubAccess access, VenueService venues, EventNews news, NotificationService notifications,
            AudienceService audiences, CampusMailer mailer, SubscriptionService subscriptions, SystemPosts systemPosts, ApplicationEventPublisher publisher) {
        this.events = events;
        this.views = views;
        this.access = access;
        this.venues = venues;
        this.news = news;
        this.notifications = notifications;
        this.audiences = audiences;
        this.mailer = mailer;
        this.subscriptions = subscriptions;
        this.systemPosts = systemPosts;
        this.publisher = publisher;
    }

    private static Query inStatus(Event event, List<EventStatus> statuses) {
        return Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("status").in(statuses.stream().map(Enum::name).toList()));
    }

    /** Moves the event on from one of {@code from}; 409 with {@code message} when another request moved it first. */
    private void transition(Event event, List<EventStatus> from, Update update, String message) {
        if (!events.update(inStatus(event, from), update)) {
            throw AppException.conflict(message, ErrorCode.INVALID_STATE);
        }
    }

    private static void assertRegistrationOpenAhead(Event event, String message) {
        if (event.getRegistrationEnd() == null || !event.getRegistrationEnd().isAfter(Instant.now())) {
            throw AppException.conflict(message, ErrorCode.INVALID_STATE);
        }
    }

    /** Completing or cancelling an event ends check-in at the door. */
    private static void closeCheckInIfOpen(Event event, AuthUser actor, Update update) {
        if (event.getCheckIn() != null && event.getCheckIn().getStatus() == CheckInStatus.OPEN) {
            update.set("checkIn.status", CheckInStatus.CLOSED.name()).set("checkIn.closedAt", Instant.now()).set("checkIn.closedBy", new ObjectId(actor.id()));
        }
    }

    // ---------------------------------------------------------------- review by the faculty mentor

    public Map<String, Object> submitEvent(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        Club club = access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_EVENTS, "You cannot submit this club's events").club();
        EventEditing.assertClubCanHostEvents(club);
        EventRules.assertStatus(event, EventStatus.EDITABLE, "Only draft events or events with requested changes can be submitted");
        EventRules.assertInFuture(event, "Events must be scheduled in the future before submission");
        if (club.getMentor() == null) {
            throw AppException.conflict("This club has no faculty mentor to review events. Contact the university admin.", ErrorCode.INVALID_STATE);
        }
        assertRegistrationOpenAhead(event, "The registration deadline has passed. Set a new deadline before submitting.");

        EventStatus from = event.getStatus();
        // Submitting reserves the venue, so the check and the claim happen under the venue lock.
        venues.withVenueLock(event.getVenue(), () -> {
            venues.assertVenueAvailable(event.getVenue(), event.getStartAt(), event.getEndAt(), event.getId(), null);
            transition(event, EventStatus.EDITABLE, new Update().set("status", EventStatus.PENDING_APPROVAL.name()).set("submittedAt", Instant.now())
                    .set("updatedBy", new ObjectId(actor.id())), "Only draft events or events with requested changes can be submitted");
        });
        event.setStatus(EventStatus.PENDING_APPROVAL);
        news.audit(event, AuditAction.EVENT_SUBMITTED, actor, from.name());
        notifications.notify(club.getMentor(), Notice.builder()
                .type(NotificationType.EVENT_REVIEW)
                .title("Event awaiting your approval")
                .message(club.getName() + " submitted \"" + event.getTitle() + "\"" + (from == EventStatus.NEEDS_CHANGES ? " with the changes you requested" : "") + ".")
                .link(EventNews.link(event))
                .email(true)
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    private Event loadForReview(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        access.assertMentor(actor, event.getClub(), "Only the club's faculty mentor can review its events");
        EventRules.assertStatus(event, List.of(EventStatus.PENDING_APPROVAL), "Event is not pending approval");
        return event;
    }

    private static Update reviewed(AuthUser actor, String comment) {
        return new Update().set("reviewedBy", new ObjectId(actor.id())).set("reviewedAt", Instant.now()).set("reviewComment", truthy(comment) ? comment.strip() : null);
    }

    public Map<String, Object> approveEvent(AuthUser actor, String eventId, String comment) {
        Event event = loadForReview(actor, eventId);
        EventRules.assertInFuture(event, "This event's start time has passed; request changes to reschedule it");
        venues.withVenueLock(event.getVenue(), () -> {
            venues.assertVenueAvailable(event.getVenue(), event.getStartAt(), event.getEndAt(), event.getId(), null);
            transition(event, List.of(EventStatus.PENDING_APPROVAL), reviewed(actor, comment).set("status", EventStatus.APPROVED.name()), "Event is not pending approval");
        });
        event.setStatus(EventStatus.APPROVED);
        news.audit(event, AuditAction.EVENT_APPROVED, actor, EventStatus.PENDING_APPROVAL.name(), comment, null);
        List<String> audience = new ArrayList<>();
        audience.add(event.getCreatedBy());
        audience.addAll(news.eventPublishers(event));
        notifications.notify(audience, Notice.builder()
                .type(NotificationType.EVENT_APPROVED)
                .title("\"" + event.getTitle() + "\" was approved")
                .message(truthy(comment) ? "Mentor note: " + comment : "It is ready to be published.")
                .link(EventNews.link(event))
                .email(true)
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> requestEventChanges(AuthUser actor, String eventId, String comment) {
        if (!truthy(comment) || comment.strip().isEmpty()) {
            throw AppException.badRequest("Describe the changes you need", ErrorCode.VALIDATION_ERROR);
        }
        Event event = loadForReview(actor, eventId);
        transition(event, List.of(EventStatus.PENDING_APPROVAL), reviewed(actor, comment).set("status", EventStatus.NEEDS_CHANGES.name()), "Event is not pending approval");
        event.setStatus(EventStatus.NEEDS_CHANGES);
        String reviewComment = comment.strip();
        news.audit(event, AuditAction.EVENT_CHANGES_REQUESTED, actor, EventStatus.PENDING_APPROVAL.name(), reviewComment, null);
        List<String> audience = new ArrayList<>();
        audience.add(event.getCreatedBy());
        audience.addAll(news.eventManagers(event));
        notifications.notify(audience, Notice.builder()
                .type(NotificationType.EVENT_CHANGES_REQUESTED)
                .title("Changes requested for \"" + event.getTitle() + "\"")
                .message(reviewComment)
                .link(EventNews.link(event))
                .email(true)
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> rejectEvent(AuthUser actor, String eventId, String reason) {
        if (!truthy(reason) || reason.strip().isEmpty()) {
            throw AppException.badRequest("Rejection reason is required", ErrorCode.VALIDATION_ERROR);
        }
        Event event = loadForReview(actor, eventId);
        transition(event, List.of(EventStatus.PENDING_APPROVAL), reviewed(actor, reason).set("status", EventStatus.REJECTED.name()), "Event is not pending approval");
        event.setStatus(EventStatus.REJECTED);
        String reviewComment = reason.strip();
        news.audit(event, AuditAction.EVENT_REJECTED, actor, EventStatus.PENDING_APPROVAL.name(), reviewComment, null);
        List<String> audience = new ArrayList<>();
        audience.add(event.getCreatedBy());
        audience.addAll(news.eventManagers(event));
        notifications.notify(audience, Notice.builder()
                .type(NotificationType.EVENT_REJECTED)
                .title("\"" + event.getTitle() + "\" was rejected")
                .message(reviewComment)
                .link(EventNews.link(event))
                .email(true)
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    // ---------------------------------------------------------------- publishing and after

    public Map<String, Object> publishEvent(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        Club club = access.assertPermission(actor, event.getClub(), ClubPermission.PUBLISH_EVENTS, "You cannot publish this club's events").club();
        EventEditing.assertClubCanHostEvents(club);
        EventRules.assertStatus(event, List.of(EventStatus.APPROVED), "Only approved events can be published");
        EventRules.assertInFuture(event, "This event's start time has passed and it can no longer be published");
        assertRegistrationOpenAhead(event, "The registration deadline has passed. Set a new deadline before publishing.");

        Instant now = Instant.now();
        venues.withVenueLock(event.getVenue(), () -> {
            venues.assertVenueAvailable(event.getVenue(), event.getStartAt(), event.getEndAt(), event.getId(), null);
            transition(event, List.of(EventStatus.APPROVED), new Update().set("status", EventStatus.PUBLISHED.name()).set("publishedAt", now)
                    .set("updatedBy", new ObjectId(actor.id())), "Only approved events can be published");
        });
        event.setStatus(EventStatus.PUBLISHED);
        event.setPublishedAt(now);
        news.audit(event, AuditAction.EVENT_PUBLISHED, actor, EventStatus.APPROVED.name());

        // Published events appear in the campus event feed for everyone. In-app it's announced to the people it's
        // for — the club's followers and members and the students it's open to — and the followers plus eligible
        // students are e-mailed (CampusMailer).
        notifications.notify(audiences.eventAudience(event, club.getId()), Notice.builder()
                .type(NotificationType.EVENT_PUBLISHED)
                .title("New event: " + event.getTitle())
                .message(club.getName() + " · " + event.getShortDescription())
                .link(EventNews.link(event))
                .exclude(List.of(actor.id()))
                // Followers get it on their phone too.
                .push(Notice.PushTargets.only(subscriptions.clubFollowerIds(club.getId())))
                .build());
        mailer.sendEventLaunchEmails(event, club, actor);
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> cancelEvent(AuthUser actor, String eventId, String reason) {
        Event event = events.find(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.PUBLISH_EVENTS, "You cannot cancel this club's events");
        List<EventStatus> cancellable = List.of(EventStatus.DRAFT, EventStatus.PENDING_APPROVAL, EventStatus.NEEDS_CHANGES, EventStatus.APPROVED, EventStatus.PUBLISHED);
        EventRules.assertStatus(event, cancellable, "This event cannot be cancelled");
        boolean wasPublished = event.getStatus() == EventStatus.PUBLISHED;
        if (wasPublished && (!truthy(reason) || reason.strip().isEmpty())) {
            throw AppException.badRequest("A reason is required to cancel a published event", ErrorCode.VALIDATION_ERROR);
        }
        EventStatus from = event.getStatus();
        String cancellationReason = truthy(reason) ? reason.strip() : null;
        Update update = new Update().set("status", EventStatus.CANCELLED.name()).set("cancelledAt", Instant.now()).set("cancellationReason", cancellationReason)
                .set("updatedBy", new ObjectId(actor.id()));
        closeCheckInIfOpen(event, actor, update);
        transition(event, List.of(from), update, "This event cannot be cancelled");
        event.setStatus(EventStatus.CANCELLED);
        news.audit(event, AuditAction.EVENT_CANCELLED, actor, from.name(), cancellationReason, null);

        if (wasPublished) {
            systemPosts.create(new SystemPosts.Post(FeedPostType.EVENT_UPDATE, context.club().getId(), event.getId(), null, actor.id(), "Cancelled: " + event.getTitle(),
                    cancellationReason, null));
            news.broadcast(event, actor, NotificationType.EVENT_CANCELLED, "\"" + event.getTitle() + "\" has been cancelled", cancellationReason);
        }
        return views.getEventDetail(actor, event.getId());
    }

    /** After the event starts, the club records what it actually spent; the mentor sees it next to the budget. */
    public Map<String, Object> recordExpenses(AuthUser actor, String eventId, List<Object> items, String note) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_EVENTS, "You cannot manage this club's events");
        if (!EventStatus.PUBLIC.contains(event.getStatus()) || event.getStartAt().isAfter(Instant.now())) {
            throw AppException.conflict("Spending can be recorded once the event has started", ErrorCode.INVALID_STATE);
        }
        List<Document> lines = new ArrayList<>();
        List<?> rows = items == null ? List.of() : items;
        double total = 0;
        for (int index = 0; index < Math.min(60, rows.size()); index++) {
            Map<String, Object> row = Values.map(rows.get(index));
            Object rawItem = row == null ? null : row.get("item");
            String item = (truthy(rawItem) ? Values.text(rawItem) : "").strip();
            double amount = row == null || !row.containsKey("amount") ? Double.NaN : Values.number(row.get("amount"));
            if (item.isEmpty()) {
                throw AppException.badRequest("Line " + (index + 1) + ": say what the money was spent on", ErrorCode.VALIDATION_ERROR);
            }
            if (!Double.isFinite(amount) || amount < 0) {
                throw AppException.badRequest("Line " + (index + 1) + ": enter the amount", ErrorCode.VALIDATION_ERROR);
            }
            double rounded = Math.round(amount * 100) / 100.0;
            EventRules.assertAtMost("amount", rounded, 10_000_000);
            total += rounded;
            lines.add(new Document("_id", new ObjectId()).append("item", item.length() > 120 ? item.substring(0, 120) : item).append("amount", rounded)
                    .append("note", Values.clean(truthy(row.get("note")) ? row.get("note") : "", 200)));
        }
        Document expenses = new Document("items", lines).append("note", Values.clean(truthy(note) ? note : "", 1000))
                .append("submittedAt", new java.util.Date()).append("submittedBy", new ObjectId(actor.id()));
        events.update(event.getId(), new Update().set("expenses", expenses));
        news.audit(event, AuditAction.EVENT_UPDATED, actor, event.getStatus().name(), null, Map.of("fields", List.of("expenses")));

        Club club = access.loadClub(event.getClub());
        if (club.getMentor() != null) {
            NumberFormat rupees = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
            rupees.setMaximumFractionDigits(3);
            notifications.notify(List.of(club.getMentor()), Notice.builder()
                    .type(NotificationType.EVENT_UPDATED)
                    .title("Spending recorded for " + event.getTitle())
                    .message("₹" + rupees.format(total) + " spent against an approved budget of ₹" + rupees.format(EventViews.budgetTotal(event)) + ".")
                    .link(EventNews.link(event))
                    .build());
        }
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> completeEvent(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_EVENTS, "You cannot complete this club's events");
        EventRules.assertStatus(event, List.of(EventStatus.PUBLISHED), "Only published events can be marked completed");
        if (event.getStartAt().isAfter(Instant.now())) {
            throw AppException.conflict("An event can only be completed after it has started", ErrorCode.INVALID_STATE);
        }
        Update update = new Update().set("status", EventStatus.COMPLETED.name()).set("completedAt", Instant.now()).set("updatedBy", new ObjectId(actor.id()));
        closeCheckInIfOpen(event, actor, update);
        transition(event, List.of(EventStatus.PUBLISHED), update, "Only published events can be marked completed");
        event.setStatus(EventStatus.COMPLETED);
        news.audit(event, AuditAction.EVENT_COMPLETED, actor, EventStatus.PUBLISHED.name());
        try {
            publisher.publishEvent(new EventCertificatesDue(event.getId()));
        } catch (RuntimeException e) {
            log.error("Certificate announcement failed for event {}: {}", event.getId(), e.getMessage());
        }
        return views.getEventDetail(actor, event.getId());
    }
}
