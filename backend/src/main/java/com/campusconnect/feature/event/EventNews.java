package com.campusconnect.feature.event;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.social.FeedPostType;
import com.campusconnect.feature.audience.AudienceService;
import com.campusconnect.feature.feed.SystemPosts;
import com.campusconnect.feature.team.Teams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Who hears about an event, and the audit trail of its changes (the notification helpers of EventService.js).
 * Everything here is a side effect: failures are logged by the services it calls and never undo the change.
 */
@Component
public class EventNews {

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final NotificationService notifications;
    private final AudienceService audiences;
    private final AuditService audit;
    private final SystemPosts systemPosts;

    public EventNews(MongoOperations mongo, ClubAccess access, NotificationService notifications, AudienceService audiences, AuditService audit, SystemPosts systemPosts) {
        this.mongo = mongo;
        this.access = access;
        this.notifications = notifications;
        this.audiences = audiences;
        this.audit = audit;
        this.systemPosts = systemPosts;
    }

    public static String link(Event event) {
        return "/events/" + event.getId();
    }

    public List<String> eventManagers(Event event) {
        return access.usersWithPermission(event.getClub(), ClubPermission.MANAGE_EVENTS);
    }

    public List<String> eventPublishers(Event event) {
        return access.usersWithPermission(event.getClub(), ClubPermission.PUBLISH_EVENTS);
    }

    /** Everyone holding or queuing for a seat hears about changes to the event. */
    public List<String> registeredUserIds(String eventId) {
        Query query = Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").in(Teams.ACTIVE));
        query.fields().include("user");
        return mongo.find(query, EventRegistration.class).stream().map(EventRegistration::getUser).toList();
    }

    /**
     * Registered students hear about it by e-mail as well (unless they muted "Your events"); the club's followers and
     * members, and the students it is open to, in-app — not the whole campus.
     */
    public void broadcast(Event event, AuthUser actor, NotificationType type, String title, String message) {
        List<String> registrants = registeredUserIds(event.getId());
        notifications.notify(registrants, Notice.builder()
                .type(type).title(title).message(message == null ? "" : message).link(link(event))
                .email(true).emailCategory(EmailCategory.EVENT_ACTIVITY)
                .exclude(List.of(actor.id()))
                .build());
        List<String> excluded = new ArrayList<>(registrants);
        excluded.add(actor.id());
        notifications.notify(audiences.eventAudience(event, event.getClub()), Notice.builder()
                .type(type).title(title).message(message == null ? "" : message).link(link(event))
                .exclude(excluded)
                .push(Notice.PushTargets.NONE)
                .build());
    }

    /** An audit entry for the event; {@code toState} is the event's status after the change. */
    public void audit(Event event, AuditAction action, AuthUser actor, String fromState, String reason, Map<String, Object> metadata) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("clubId", event.getClub());
        if (metadata != null) {
            details.putAll(metadata);
        }
        audit.record(action, actor.id(), "Event", event.getId(), fromState, event.getStatus() == null ? null : event.getStatus().name(), reason, details);
    }

    public void audit(Event event, AuditAction action, AuthUser actor, String fromState) {
        audit(event, action, actor, fromState, null, null);
    }

    public void notifyMentorOfChanges(Club club, Event event, List<String> fields, boolean afterApproval) {
        notifications.notify(club.getMentor(), Notice.builder()
                .type(NotificationType.EVENT_CHANGES_REVIEW)
                .title(afterApproval ? "\"" + event.getTitle() + "\" was changed after approval" : "Changes to \"" + event.getTitle() + "\" need your approval")
                .message(club.getName() + " changed the " + EventRules.labelList(fields) + "."
                        + (afterApproval ? " Please review the event again." : " The event stays as it is until you approve."))
                .link(link(event))
                .email(true)
                .build());
    }

    /** An update note is kept on the event page's "Updates" section (not in the campus feed) and sent to everyone involved. */
    public void postUpdateNote(Event event, AuthUser actor, String note) {
        systemPosts.create(new SystemPosts.Post(FeedPostType.EVENT_UPDATE, event.getClub(), event.getId(), null, actor.id(), "Update: " + event.getTitle(), note, null));
        broadcast(event, actor, NotificationType.EVENT_UPDATED, "Update for " + event.getTitle(), note);
    }
}
