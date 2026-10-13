package com.campusconnect.feature.registration;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.util.Updates;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.TeamRole;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.feature.event.EventStore;
import com.campusconnect.feature.ticket.TicketService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * A full event keeps a first-come, first-served waitlist (services/WaitlistService.js). Whenever a seat frees up (a
 * cancellation, an organiser removing someone, a bigger capacity) the queue is promoted in order until the event is
 * full again. Every step is one atomic conditional update, so parallel cancellations can never overfill the event
 * or promote the same student twice, on any number of replicas.
 */
@Slf4j
@Service
public class Waitlist {

    static final int MAX_PROMOTIONS_PER_RUN = 500;

    private final MongoOperations mongo;
    private final EventStore events;
    private final TicketService tickets;
    private final NotificationService notifications;
    private final AuditService audit;

    public Waitlist(MongoOperations mongo, EventStore events, TicketService tickets, NotificationService notifications, AuditService audit) {
        this.mongo = mongo;
        this.events = events;
        this.tickets = tickets;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** First in the queue first: by the time the place was taken, then by id. */
    public static Sort queueOrder() {
        return Sort.by(Sort.Order.asc("waitlistedAt"), Sort.Order.asc("_id"));
    }

    /** Places in the queue belong to students or to whole teams (held by the leader); members follow their leader. */
    public static Criteria queueEntry(String eventId) {
        return Criteria.where("event").is(new ObjectId(eventId)).and("status").is(RegistrationStatus.WAITLISTED.name()).and("teamRole").ne(TeamRole.MEMBER.name());
    }

    /** 1-based place in the queue, or null when the registration is not waitlisted. */
    public Integer waitlistPosition(EventRegistration registration) {
        if (registration == null || registration.getStatus() != RegistrationStatus.WAITLISTED) {
            return null;
        }
        if (registration.getTeamRole() == TeamRole.MEMBER) {
            EventRegistration leader = registration.getTeam() == null ? null : mongo.findOne(Query.query(Criteria.where("team").is(new ObjectId(registration.getTeam()))
                    .and("teamRole").is(TeamRole.LEADER.name())), EventRegistration.class);
            return leader == null ? null : waitlistPosition(leader);
        }
        Criteria ahead = queueEntry(registration.getEvent()).orOperator(
                Criteria.where("waitlistedAt").lt(registration.getWaitlistedAt()),
                Criteria.where("waitlistedAt").is(registration.getWaitlistedAt()).and("_id").lt(new ObjectId(registration.getId())));
        return (int) mongo.count(Query.query(ahead), EventRegistration.class) + 1;
    }

    /** Takes a seat with one conditional $inc, so concurrent registrations cannot overfill. Null when full or closed. */
    public Event reserveSeat(Event event) {
        Criteria seat = Criteria.where("_id").is(new ObjectId(event.getId())).and("status").is(EventStatus.PUBLISHED.name()).and("startAt").gt(Instant.now());
        if (event.getMaxParticipants() != null && event.getMaxParticipants() > 0) {
            seat = seat.and("registeredCount").lt(event.getMaxParticipants());
        }
        return events.modify(Query.query(seat), new Update().inc("registeredCount", 1), true);
    }

    public void releaseSeat(String eventId) {
        events.update(Query.query(Criteria.where("_id").is(new ObjectId(eventId)).and("registeredCount").gt(0)), new Update().inc("registeredCount", -1));
    }

    public void adjustWaitlistCount(String eventId, int delta) {
        Criteria criteria = Criteria.where("_id").is(new ObjectId(eventId));
        if (delta < 0) {
            criteria = criteria.and("waitlistCount").gt(0);
        }
        events.update(Query.query(criteria), new Update().inc("waitlistCount", delta));
    }

    /** The event's current counts, as returned after registering or cancelling. */
    public Map<String, Object> counts(String eventId) {
        Event fresh = events.find(eventId);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("registeredCount", fresh.getRegisteredCount());
        counts.put("maxParticipants", fresh.getMaxParticipants());
        counts.put("waitlistCount", fresh.getWaitlistCount());
        return counts;
    }

    /**
     * Moves waitlisted students into free seats, first in first out. Returns how many were promoted. Stops as soon as
     * the event is full, has started, or is no longer published. Never throws: a failure leaves the queue as it is.
     */
    public int promoteFromWaitlist(String eventId, String reason) {
        int promoted = 0;
        try {
            while (promoted < MAX_PROMOTIONS_PER_RUN) {
                Event event = events.findOrNull(eventId);
                if (event == null || event.getStatus() != EventStatus.PUBLISHED || !event.getStartAt().isAfter(Instant.now())) {
                    break;
                }
                EventRegistration next = mongo.findOne(Query.query(queueEntry(eventId)).with(queueOrder()), EventRegistration.class);
                if (next == null || reserveSeat(event) == null) {
                    break;
                }
                Instant now = Instant.now();
                EventRegistration claimed = tickets.withTicketRetry(ticket -> mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(new ObjectId(next.getId())).and("status").is(RegistrationStatus.WAITLISTED.name())),
                        Updates.touch(ticket.applyTo(new Update().set("status", RegistrationStatus.REGISTERED.name()).set("registeredAt", now).set("promotedAt", now))),
                        FindAndModifyOptions.options().returnNew(true),
                        EventRegistration.class));
                if (claimed == null) {
                    // They left the waitlist (or another run promoted them) meanwhile; give the seat back and retry.
                    releaseSeat(eventId);
                    continue;
                }
                adjustWaitlistCount(eventId, -1);
                promoted++;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("eventId", eventId);
                metadata.put("reason", reason);
                audit.record(AuditAction.WAITLIST_PROMOTED, claimed.getUser(), "EventRegistration", claimed.getId(),
                        RegistrationStatus.WAITLISTED.name(), RegistrationStatus.REGISTERED.name(), null, metadata);

                // A team moves in together: the members who already accepted follow their leader, each with a ticket.
                List<EventRegistration> teammates = claimed.getTeam() == null ? List.of() : mongo.find(Query.query(Criteria.where("team").is(new ObjectId(claimed.getTeam()))
                        .and("teamRole").is(TeamRole.MEMBER.name()).and("status").is(RegistrationStatus.WAITLISTED.name())), EventRegistration.class);
                for (EventRegistration teammate : teammates) {
                    tickets.withTicketRetry(ticket -> mongo.updateFirst(
                            Query.query(Criteria.where("_id").is(new ObjectId(teammate.getId())).and("status").is(RegistrationStatus.WAITLISTED.name())),
                            Updates.touch(ticket.applyTo(new Update().set("status", RegistrationStatus.REGISTERED.name()).set("registeredAt", now).set("promotedAt", now))),
                            EventRegistration.class));
                }
                List<String> people = new ArrayList<>();
                people.add(claimed.getUser());
                teammates.forEach(teammate -> people.add(teammate.getUser()));
                notifications.notify(people, Notice.builder()
                        .type(NotificationType.REGISTRATION_CONFIRMED)
                        .title("You're in! A spot opened up for " + event.getTitle())
                        .message("You've moved off the waitlist and are now registered. See you on " + CampusTime.formatDateKey(CampusTime.toDateKey(event.getEventDate()))
                                + " at " + event.getStartTime() + ". If you can no longer make it, cancel so the next person gets your seat.")
                        .link("/events/" + eventId)
                        .build());
                // Always e-mailed (as the ticket): this is the confirmation of a seat they asked for.
                tickets.sendTicketEmail(claimed.getId(), "promoted");
                teammates.forEach(teammate -> tickets.sendTicketEmail(teammate.getId(), "promoted"));
            }
        } catch (RuntimeException e) {
            log.error("Waitlist promotion failed for event {}: {}", eventId, e.getMessage());
        }
        return promoted;
    }
}
