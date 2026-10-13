package com.campusconnect.feature.registration;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Updates;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.TeamRole;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.feature.event.EventStore;
import com.campusconnect.feature.team.Teams;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Cancelling a registration or leaving the waitlist (RegistrationService.cancelRegistration). A freed seat goes to
 * the first person waiting; a team member leaves their team; a leader withdraws the whole team. Switching to an
 * event at the same time cancels through here too, so it behaves exactly as if the student had cancelled.
 */
@Service
public class RegistrationCancellation {

    private final MongoOperations mongo;
    private final EventStore events;
    private final Teams teams;
    private final Waitlist waitlist;
    private final NotificationService notifications;
    private final AuditService audit;

    public RegistrationCancellation(MongoOperations mongo, EventStore events, Teams teams, Waitlist waitlist, NotificationService notifications, AuditService audit) {
        this.mongo = mongo;
        this.events = events;
        this.teams = teams;
        this.waitlist = waitlist;
        this.notifications = notifications;
        this.audit = audit;
    }

    private static Criteria activeRegistration(Event event, AuthUser actor) {
        return Criteria.where("event").is(event.getId()).and("user").is(actor.id()).and("status").in(Teams.ACTIVE);
    }

    public Map<String, Object> cancelRegistration(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        if (event.getStatus() != EventStatus.PUBLISHED || !event.getStartAt().isAfter(Instant.now())) {
            throw AppException.conflict("Registrations can only be cancelled before the event starts", ErrorCode.INVALID_STATE);
        }

        EventRegistration current = mongo.findOne(Query.query(activeRegistration(event, actor)), EventRegistration.class);
        if (current != null && current.getTeamRole() == TeamRole.MEMBER) {
            Map<String, Object> result = new LinkedHashMap<>(teams.leaveTeam(actor, event, current));
            result.put("leftWaitlist", false);
            result.put("promoted", 0);
            result.putAll(waitlist.counts(event.getId()));
            return result;
        }

        // The update returns the registration as it was, so its old status says what was freed.
        EventRegistration registration = mongo.findAndModify(Query.query(activeRegistration(event, actor)),
                Updates.set("status", RegistrationStatus.CANCELLED.name()), FindAndModifyOptions.options().returnNew(false), EventRegistration.class);
        if (registration == null) {
            throw AppException.notFound("You are not registered for this event");
        }

        boolean wasWaitlisted = registration.getStatus() == RegistrationStatus.WAITLISTED;
        if (!wasWaitlisted) {
            // In-app only: the student did this themselves; it just records that the ticket is gone.
            notifications.notify(actor.id(), Notice.builder()
                    .type(NotificationType.REGISTRATION_REMOVED)
                    .title("You cancelled your registration for " + event.getTitle())
                    .message("Your ticket is no longer valid. You can register again while registration is open.")
                    .link("/events/" + event.getId())
                    .build());
        }
        if (wasWaitlisted) {
            waitlist.adjustWaitlistCount(event.getId(), -1);
        } else {
            waitlist.releaseSeat(event.getId());
        }
        audit.record(wasWaitlisted ? AuditAction.WAITLIST_LEFT : AuditAction.REGISTRATION_CANCELLED, actor.id(), "EventRegistration", registration.getId(),
                registration.getStatus().name(), RegistrationStatus.CANCELLED.name(), null, Map.of("eventId", event.getId()));

        boolean leader = registration.getTeamRole() == TeamRole.LEADER;
        if (leader) {
            teams.disbandTeam(registration.getTeam(), event, actor, false, null);
        }
        int promoted = wasWaitlisted ? 0 : waitlist.promoteFromWaitlist(event.getId(), "registration_cancelled");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("leftWaitlist", wasWaitlisted);
        result.put("disbandedTeam", leader);
        result.put("promoted", promoted);
        result.putAll(waitlist.counts(event.getId()));
        return result;
    }
}
