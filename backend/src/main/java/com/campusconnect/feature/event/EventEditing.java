package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.Values.text;
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
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.event.BudgetItem;
import com.campusconnect.domain.event.EquipmentItem;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventContact;
import com.campusconnect.domain.event.EventEligibility;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventRevision;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RevisionStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.social.FeedPostType;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.domain.venue.VenueStatus;
import com.campusconnect.feature.feed.SystemPosts;
import com.campusconnect.feature.registration.RegistrationForms;
import com.campusconnect.feature.registration.Waitlist;
import com.campusconnect.feature.team.Teams;
import com.campusconnect.feature.venue.VenueService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Creating and editing events (EventService.js createDraft, updateEvent and the revision functions). Before an event
 * starts every detail can be edited: drafts change directly; an approved event goes back to its faculty mentor; a
 * published event keeps its live details while the proposed changes are reviewed, and the club publishes them once
 * the mentor approves.
 */
@Slf4j
@Service
public class EventEditing {

    private final MongoOperations mongo;
    private final EventStore events;
    private final EventViews views;
    private final EventJson eventJson;
    private final ClubAccess access;
    private final VenueService venues;
    private final EventNews news;
    private final RegistrationForms forms;
    private final Waitlist waitlist;
    private final SystemPosts systemPosts;
    private final NotificationService notifications;
    private final ApplicationEventPublisher publisher;

    public EventEditing(MongoOperations mongo, EventStore events, EventViews views, EventJson eventJson, ClubAccess access, VenueService venues, EventNews news,
            RegistrationForms forms, Waitlist waitlist, SystemPosts systemPosts, NotificationService notifications, ApplicationEventPublisher publisher) {
        this.mongo = mongo;
        this.events = events;
        this.views = views;
        this.eventJson = eventJson;
        this.access = access;
        this.venues = venues;
        this.news = news;
        this.forms = forms;
        this.waitlist = waitlist;
        this.systemPosts = systemPosts;
        this.notifications = notifications;
        this.publisher = publisher;
    }

    static void assertClubCanHostEvents(Club club) {
        if (club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("Only active clubs can create or publish events", ErrorCode.CLUB_NOT_ACTIVE);
        }
    }

    Venue loadActiveVenue(Object venueId) {
        String id = Values.id(venueId);
        Venue venue = id != null && ObjectId.isValid(id) ? mongo.findById(id, Venue.class) : null;
        if (venue == null || venue.getStatus() != VenueStatus.ACTIVE) {
            throw AppException.badRequest("Selected venue is not available", ErrorCode.VALIDATION_ERROR);
        }
        return venue;
    }

    /** The organiser: the chosen member, or the actor when they are a member themselves (else nobody). */
    String resolveOrganizer(String clubId, Object organizerId, AuthUser actor) {
        String candidate = truthy(organizerId) ? text(organizerId) : actor.id();
        boolean member = ObjectId.isValid(candidate) && access.membership(candidate, clubId) != null;
        if (!member) {
            if (truthy(organizerId)) {
                throw AppException.badRequest("Organizer must be a member of the club", ErrorCode.VALIDATION_ERROR);
            }
            return null;
        }
        return candidate;
    }

    private long activeRegistrations(String eventId) {
        return mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").in(Teams.ACTIVE)), EventRegistration.class);
    }

    private void certificatesDue(Event event) {
        try {
            publisher.publishEvent(new EventCertificatesDue(event.getId()));
        } catch (RuntimeException e) {
            log.error("Certificate announcement failed for event {}: {}", event.getId(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- creating

    public Map<String, Object> createDraft(AuthUser actor, EventPayload payload) {
        ClubContext context = access.assertPermission(actor, text(payload.get("club")), ClubPermission.MANAGE_EVENTS, "You cannot create events for this club");
        Club club = context.club();
        assertClubCanHostEvents(club);

        Venue venue = loadActiveVenue(payload.get("venue"));
        Map<String, Object> eligibility = EventRules.normalizeEligibility(payload.get("eligibility"));
        VenueService.assertVenueFitsAudience(venue, VenueService.audienceOf(club, EventRules.departmentsOf(eligibility)));
        EventRules.Schedule schedule = EventRules.buildSchedule(payload.get("eventDate"), payload.get("endDate"), payload.get("startTime"), payload.get("endTime"),
                payload.get("registrationStart"), payload.get("registrationEnd"));
        EventRules.TeamSettings team = EventRules.normalizeTeamSettings(payload, null);
        venues.assertVenueAvailable(venue.getId(), schedule.startAt(), schedule.endAt(), null, null);

        Event event = new Event();
        event.setTitle(text(payload.get("title")).strip());
        event.setShortDescription(text(payload.get("shortDescription")).strip());
        event.setDescription(text(payload.get("description")).strip());
        event.setCategory(text(payload.get("category")));
        event.setPoster(truthy(payload.get("poster")) ? text(payload.get("poster")) : null);
        event.setClub(club.getId());
        event.setVenue(venue.getId());
        event.setEventDate(schedule.eventDate());
        event.setEndDate(schedule.endDate());
        event.setStartTime(schedule.startTime());
        event.setEndTime(schedule.endTime());
        event.setStartAt(schedule.startAt());
        event.setEndAt(schedule.endAt());
        event.setRegistrationStart(schedule.registrationStart());
        event.setRegistrationEnd(schedule.registrationEnd());
        event.setParticipationMode(team.participationMode());
        event.setMinTeamSize(team.minTeamSize());
        event.setMaxTeamSize(team.maxTeamSize());
        event.setMaxParticipants(EventRules.assertCapacityFits(payload.get("maxParticipants"), venue, 0, team.maxTeamSize()));
        event.setEligibility(eligibilityOf(eligibility));
        event.setCertificatesEnabled(truthy(payload.get("certificatesEnabled")));
        event.setRegistrationForm(forms.normalizeRegistrationForm(Values.map(payload.get("registrationForm")), team.participationMode() == ParticipationMode.TEAM));
        EventRules.Budget budget = EventRules.normalizeBudget(payload.get("budgetItems"), payload.get("equipment"), payload.get("budgetNote"));
        event.setBudgetItems(new ArrayList<>(budget.budgetItems().stream().map(EventEditing::budgetItem).toList()));
        event.setEquipment(new ArrayList<>(budget.equipment().stream().map(EventEditing::equipmentItem).toList()));
        event.setBudgetNote(budget.budgetNote());
        event.setRules(truthy(payload.get("rules")) ? text(payload.get("rules")).strip() : "");
        event.setContact(contactOf(EventRules.normalizeContact(payload.get("contact"))));
        event.setOrganizer(resolveOrganizer(club.getId(), payload.get("organizer"), actor));
        event.setCreatedBy(actor.id());
        event.setUpdatedBy(actor.id());
        event.setStatus(EventStatus.DRAFT);
        event = events.insert(event);

        news.audit(event, AuditAction.EVENT_CREATED, actor, null);
        return views.getEventDetail(actor, event.getId());
    }

    @SuppressWarnings("unchecked")
    private static EventEligibility eligibilityOf(Map<String, Object> eligibility) {
        EventEligibility result = new EventEligibility();
        result.setDepartments(new ArrayList<>((List<String>) eligibility.get("departments")));
        result.setBatches(new ArrayList<>((List<String>) eligibility.get("batches")));
        result.setNotes((String) eligibility.get("notes"));
        return result;
    }

    private static EventContact contactOf(Map<String, Object> contact) {
        EventContact result = new EventContact();
        result.setName((String) contact.get("name"));
        result.setEmail((String) contact.get("email"));
        result.setPhone((String) contact.get("phone"));
        return result;
    }

    private static BudgetItem budgetItem(Map<String, Object> row) {
        BudgetItem item = new BudgetItem();
        item.setItem((String) row.get("item"));
        item.setQuantity(((Number) row.get("quantity")).intValue());
        item.setUnitCost(((Number) row.get("unitCost")).doubleValue());
        item.setNote((String) row.get("note"));
        return item;
    }

    private static EquipmentItem equipmentItem(Map<String, Object> row) {
        EquipmentItem item = new EquipmentItem();
        item.setName((String) row.get("name"));
        item.setQuantity(((Number) row.get("quantity")).intValue());
        item.setNote((String) row.get("note"));
        return item;
    }

    // ---------------------------------------------------------------- editing

    /** What an edit would change: the new values of the changed fields, and the fields to list as changed. */
    record Proposal(Map<String, Object> next, List<String> fields) {
    }

    /** Works out what an edit would change, validating the event as it would be afterwards. */
    Proposal proposeChanges(Event event, EventPayload payload, AuthUser actor) {
        boolean venueChanged = payload.has("venue") && !Objects.equals(text(payload.get("venue")), event.getVenue());
        Venue venue = venueChanged ? loadActiveVenue(payload.get("venue")) : mongo.findById(event.getVenue(), Venue.class);
        EventRules.TeamSettings team = EventRules.normalizeTeamSettings(payload, event);
        if (team.differsFrom(event) && activeRegistrations(event.getId()) > 0) {
            throw AppException.conflict("Team settings can't change once students have registered", ErrorCode.INVALID_STATE);
        }
        Map<String, Object> proposed = new LinkedHashMap<>(team.fields());

        for (String field : List.of("title", "shortDescription", "description", "category", "rules")) {
            if (payload.has(field)) {
                Object value = payload.get(field);
                proposed.put(field, value instanceof String string ? string.strip() : value);
            }
        }
        if (payload.has("poster")) {
            proposed.put("poster", truthy(payload.get("poster")) ? payload.get("poster") : null);
        }
        if (payload.has("contact")) {
            proposed.put("contact", EventRules.normalizeContact(payload.get("contact")));
        }
        if (payload.has("eligibility")) {
            proposed.put("eligibility", EventRules.normalizeEligibility(payload.get("eligibility")));
        }
        if (payload.has("organizer")) {
            proposed.put("organizer", Values.objectId(resolveOrganizer(event.getClub(), payload.get("organizer"), actor)));
        }
        if (payload.has("budgetItems") || payload.has("equipment") || payload.has("budgetNote")) {
            EventRules.Budget budget = EventRules.normalizeBudget(
                    payload.has("budgetItems") ? payload.get("budgetItems") : eventJson.currentValue(event, "budgetItems"),
                    payload.has("equipment") ? payload.get("equipment") : eventJson.currentValue(event, "equipment"),
                    payload.has("budgetNote") ? payload.get("budgetNote") : event.getBudgetNote());
            proposed.put("budgetItems", budget.budgetItems());
            proposed.put("equipment", budget.equipment());
            proposed.put("budgetNote", budget.budgetNote());
        }
        if (venueChanged) {
            proposed.put("venue", new ObjectId(venue.getId()));
        }

        boolean timingChanged = EventRules.scheduleChanged(event, payload);
        if (timingChanged) {
            EventRules.Schedule schedule = EventRules.buildSchedule(
                    truthy(payload.get("eventDate")) ? payload.get("eventDate") : EventRules.dateKey(event.getEventDate()),
                    payload.has("endDate") ? (truthy(payload.get("endDate")) ? payload.get("endDate") : null) : event.getEndDate(),
                    truthy(payload.get("startTime")) ? payload.get("startTime") : event.getStartTime(),
                    truthy(payload.get("endTime")) ? payload.get("endTime") : event.getEndTime(),
                    truthy(payload.get("registrationStart")) ? payload.get("registrationStart") : event.getRegistrationStart(),
                    truthy(payload.get("registrationEnd")) ? payload.get("registrationEnd") : event.getRegistrationEnd());
            proposed.putAll(schedule.fields());
            // The form sends whole minutes: a registration time that only lost its seconds hasn't changed.
            if (EventRules.sameInstant(proposed.get("registrationStart"), event.getRegistrationStart())) {
                proposed.put("registrationStart", event.getRegistrationStart());
            }
            if (EventRules.sameInstant(proposed.get("registrationEnd"), event.getRegistrationEnd())) {
                proposed.put("registrationEnd", event.getRegistrationEnd());
            }
        }

        if (venueChanged || proposed.containsKey("eligibility")) {
            Query query = Query.query(Criteria.where("_id").is(new ObjectId(event.getClub())));
            query.fields().include("allDepartments", "departmentCodes");
            Club club = mongo.findOne(query, Club.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> eligibility = proposed.containsKey("eligibility") ? (Map<String, Object>) proposed.get("eligibility")
                    : eventJson.plain(event.getEligibility(), LinkedHashMap.class);
            VenueService.assertVenueFitsAudience(venue, VenueService.audienceOf(club, EventRules.departmentsOf(eligibility)));
        }

        Object limit = payload.has("maxParticipants") ? payload.get("maxParticipants") : event.getMaxParticipants();
        if (payload.has("maxParticipants") || venueChanged || team.maxTeamSize() != event.getMaxTeamSize()) {
            proposed.put("maxParticipants", EventRules.assertCapacityFits(limit, venue, event.getRegisteredCount(), team.maxTeamSize()));
        }

        List<String> changed = proposed.keySet().stream().filter(field -> !EventRules.sameValue(proposed.get(field), eventJson.currentValue(event, field))).toList();
        if (changed.isEmpty()) {
            return new Proposal(new LinkedHashMap<>(), List.of());
        }
        if (venueChanged || timingChanged) {
            venues.assertVenueAvailable(venue.getId(), proposed.containsKey("startAt") ? (Instant) proposed.get("startAt") : event.getStartAt(),
                    proposed.containsKey("endAt") ? (Instant) proposed.get("endAt") : event.getEndAt(), event.getId(), null);
        }
        Map<String, Object> next = new LinkedHashMap<>();
        changed.forEach(field -> next.put(field, proposed.get(field)));
        // Moving the event keeps start and end together even when only one of them differs.
        if (timingChanged) {
            EventRules.DERIVED_FIELDS.forEach(field -> next.put(field, proposed.get(field)));
        }
        return new Proposal(next, changed.stream().filter(field -> !EventRules.DERIVED_FIELDS.contains(field)).toList());
    }

    /** $set of changed fields; budget and equipment rows get their own ids, as Mongoose gave new sub-documents. */
    static Update changesUpdate(Map<String, Object> changes) {
        Update update = new Update();
        changes.forEach((field, value) -> update.set(field, ("budgetItems".equals(field) || "equipment".equals(field)) ? withRowIds(value) : value));
        return update;
    }

    private static Object withRowIds(Object rows) {
        List<?> list = Values.list(rows);
        if (list == null) {
            return rows;
        }
        List<Document> withIds = new ArrayList<>();
        for (Object row : list) {
            Map<String, Object> fields = Values.map(row);
            Document document = new Document("_id", fields != null && fields.get("_id") != null ? fields.get("_id") : new ObjectId());
            if (fields != null) {
                fields.forEach((key, value) -> {
                    if (!"_id".equals(key)) {
                        document.put(key, value);
                    }
                });
            }
            withIds.add(document);
        }
        return withIds;
    }

    public Map<String, Object> updateEvent(AuthUser actor, String eventId, EventPayload payload) {
        Event event = events.find(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_EVENTS, "You cannot edit this club's events");
        Club club = context.club();
        boolean editsDetails = EventRules.EDIT_FIELDS.stream().anyMatch(payload::has);
        String note = (truthy(payload.get("updateNote")) ? text(payload.get("updateNote")) : "").strip();
        ObjectId editor = new ObjectId(actor.id());

        // Certificates are the club's own decision: switched on or off directly, never reviewed by the mentor.
        if (payload.has("certificatesEnabled") && truthy(payload.get("certificatesEnabled")) != event.isCertificatesEnabled()) {
            if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.REJECTED) {
                throw AppException.conflict("This event no longer issues certificates", ErrorCode.INVALID_STATE);
            }
            boolean enabled = truthy(payload.get("certificatesEnabled"));
            events.update(event.getId(), new Update().set("certificatesEnabled", enabled).set("updatedBy", editor));
            event.setCertificatesEnabled(enabled);
            news.audit(event, AuditAction.EVENT_UPDATED, actor, event.getStatus().name(), null, Map.of("fields", List.of("certificatesEnabled")));
            if (enabled && event.getStatus() == EventStatus.COMPLETED) {
                certificatesDue(event);
            }
        }

        // Opening or closing registration is an operational switch, not a change to the event's details.
        if (payload.has("registrationClosed")) {
            if (event.getStatus() != EventStatus.PUBLISHED || !event.getStartAt().isAfter(Instant.now())) {
                throw AppException.conflict("Registration can only be opened or closed for upcoming published events", ErrorCode.INVALID_STATE);
            }
            boolean closed = truthy(payload.get("registrationClosed"));
            events.update(event.getId(), new Update().set("registrationClosed", closed).set("updatedBy", editor));
            event.setRegistrationClosed(closed);
            news.audit(event, AuditAction.EVENT_UPDATED, actor, event.getStatus().name(), null, Map.of("fields", List.of("registrationClosed")));
        }

        if (!editsDetails) {
            if (!note.isEmpty() && event.getStatus() == EventStatus.PUBLISHED) {
                news.postUpdateNote(event, actor, note);
            }
            return views.getEventDetail(actor, event.getId());
        }

        if (!List.of(EventStatus.DRAFT, EventStatus.NEEDS_CHANGES, EventStatus.PENDING_APPROVAL, EventStatus.APPROVED, EventStatus.PUBLISHED).contains(event.getStatus())) {
            throw AppException.conflict("This event can no longer be edited", ErrorCode.INVALID_STATE);
        }
        EventRules.assertInFuture(event, "This event has already started, so its details can no longer be changed");
        if (event.getStatus() == EventStatus.PENDING_APPROVAL) {
            throw AppException.conflict("This event is with your faculty mentor for review. You can edit it again once they respond.", ErrorCode.INVALID_STATE);
        }

        Proposal proposal = proposeChanges(event, payload, actor);
        Map<String, Object> next = proposal.next();
        List<String> fields = proposal.fields();
        String status = event.getStatus().name();

        if (EventStatus.EDITABLE.contains(event.getStatus())) {
            events.update(event.getId(), changesUpdate(next).set("updatedBy", editor));
            news.audit(event, AuditAction.EVENT_UPDATED, actor, status, null, Map.of("fields", fields));
            return views.getEventDetail(actor, event.getId());
        }

        if (event.getStatus() == EventStatus.APPROVED) {
            // Not public yet: apply the edit and send the event back to the mentor.
            if (fields.isEmpty()) {
                return views.getEventDetail(actor, event.getId());
            }
            if (club.getMentor() == null) {
                throw AppException.conflict("This club has no faculty mentor to review events. Contact the university admin.", ErrorCode.INVALID_STATE);
            }
            String venueId = next.containsKey("venue") ? Values.id(next.get("venue")) : event.getVenue();
            venues.withVenueLock(venueId, () -> {
                if (next.containsKey("venue") || next.containsKey("startAt")) {
                    venues.assertVenueAvailable(venueId, next.containsKey("startAt") ? (Instant) next.get("startAt") : event.getStartAt(),
                            next.containsKey("endAt") ? (Instant) next.get("endAt") : event.getEndAt(), event.getId(), null);
                }
                events.update(event.getId(), changesUpdate(next).set("status", EventStatus.PENDING_APPROVAL.name()).set("submittedAt", Instant.now())
                        .set("reviewComment", null).set("updatedBy", editor));
            });
            Event saved = events.find(event.getId());
            news.audit(saved, AuditAction.EVENT_UPDATED, actor, EventStatus.APPROVED.name(), null, Map.of("fields", fields));
            news.audit(saved, AuditAction.EVENT_SUBMITTED, actor, EventStatus.APPROVED.name(), "Edited after approval", null);
            news.notifyMentorOfChanges(club, saved, fields, true);
            return views.getEventDetail(actor, event.getId());
        }

        // Published: the live event stays as it is; the changes wait for the mentor.
        if (fields.isEmpty()) {
            if (event.getRevision() != null) {
                events.update(event.getId(), new Update().set("revision", null));
                news.audit(event, AuditAction.EVENT_REVISION_DISCARDED, actor, status, "Edits matched the live event", null);
            }
            if (!note.isEmpty()) {
                news.postUpdateNote(event, actor, note);
            }
            return views.getEventDetail(actor, event.getId());
        }
        if (club.getMentor() == null) {
            throw AppException.conflict("This club has no faculty mentor to review the changes. Contact the university admin.", ErrorCode.INVALID_STATE);
        }
        EventRevision revision = new EventRevision();
        revision.setStatus(RevisionStatus.PENDING_APPROVAL);
        revision.setChanges(next);
        revision.setFields(new ArrayList<>(fields));
        revision.setNote(note);
        revision.setRequestedBy(actor.id());
        revision.setRequestedAt(Instant.now());
        events.update(event.getId(), new Update().set("revision", revision));
        news.audit(event, AuditAction.EVENT_REVISION_SUBMITTED, actor, status, null, Map.of("fields", fields));
        news.notifyMentorOfChanges(club, event, fields, false);
        return views.getEventDetail(actor, event.getId());
    }

    // ---------------------------------------------------------------- reviewing changes to a published event

    private Event loadRevisionForReview(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        access.assertMentor(actor, event.getClub(), "Only the club's faculty mentor can review changes to its events");
        if (event.getRevision() == null || event.getRevision().getStatus() != RevisionStatus.PENDING_APPROVAL) {
            throw AppException.conflict("There are no changes waiting for review", ErrorCode.INVALID_STATE);
        }
        return event;
    }

    /** Records the mentor's decision on the pending edit; false when it was decided meanwhile. */
    private Event markRevisionReviewed(Event event, AuthUser actor, RevisionStatus status, String comment) {
        boolean marked = events.update(
                Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("revision.status").is(RevisionStatus.PENDING_APPROVAL.name())),
                new Update().set("revision.status", status.name()).set("revision.reviewedBy", new ObjectId(actor.id()))
                        .set("revision.reviewedAt", Instant.now()).set("revision.reviewComment", comment));
        if (!marked) {
            throw AppException.conflict("There are no changes waiting for review", ErrorCode.INVALID_STATE);
        }
        return events.find(event.getId());
    }

    private List<String> revisionAudience(Event event) {
        List<String> audience = new ArrayList<>();
        audience.add(event.getRevision().getRequestedBy());
        audience.addAll(news.eventManagers(event));
        return audience;
    }

    private static String reviewComment(String comment) {
        return truthy(comment) ? comment.strip() : null;
    }

    private void assertVenueStillFree(Event event, Map<String, Object> changes) {
        if (truthy(changes.get("venue")) || truthy(changes.get("startAt"))) {
            venues.assertVenueAvailable(truthy(changes.get("venue")) ? Values.id(changes.get("venue")) : event.getVenue(),
                    truthy(changes.get("startAt")) ? Values.instant(changes.get("startAt")) : event.getStartAt(),
                    truthy(changes.get("endAt")) ? Values.instant(changes.get("endAt")) : event.getEndAt(), event.getId(), null);
        }
    }

    public Map<String, Object> approveEventChanges(AuthUser actor, String eventId, String comment) {
        Event event = loadRevisionForReview(actor, eventId);
        EventRules.assertInFuture(event, "This event has already started, so its details can no longer be changed");
        assertVenueStillFree(event, event.getRevision().getChanges());

        Event reviewed = markRevisionReviewed(event, actor, RevisionStatus.APPROVED, reviewComment(comment));
        news.audit(reviewed, AuditAction.EVENT_REVISION_APPROVED, actor, reviewed.getStatus().name(), reviewed.getRevision().getReviewComment(), null);
        List<String> audience = revisionAudience(reviewed);
        audience.addAll(news.eventPublishers(reviewed));
        notifications.notify(audience, Notice.builder()
                .type(NotificationType.EVENT_APPROVED)
                .title("Changes to \"" + reviewed.getTitle() + "\" were approved")
                .message((truthy(comment) ? "Mentor note: " + comment.strip() + ". " : "") + "Publish them to update the live event.")
                .link(EventNews.link(reviewed))
                .email(true)
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> requestEventChangesRevision(AuthUser actor, String eventId, String comment) {
        if (!truthy(comment) || comment.strip().isEmpty()) {
            throw AppException.badRequest("Describe what needs to change", ErrorCode.VALIDATION_ERROR);
        }
        Event event = loadRevisionForReview(actor, eventId);
        Event reviewed = markRevisionReviewed(event, actor, RevisionStatus.NEEDS_CHANGES, reviewComment(comment));
        news.audit(reviewed, AuditAction.EVENT_REVISION_CHANGES_REQUESTED, actor, reviewed.getStatus().name(), reviewed.getRevision().getReviewComment(), null);
        notifications.notify(revisionAudience(reviewed), Notice.builder()
                .type(NotificationType.EVENT_CHANGES_REQUESTED)
                .title("Your mentor asked for changes to your edit of \"" + reviewed.getTitle() + "\"")
                .message(reviewed.getRevision().getReviewComment())
                .link(EventNews.link(reviewed))
                .email(true)
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> rejectEventChanges(AuthUser actor, String eventId, String reason) {
        if (!truthy(reason) || reason.strip().isEmpty()) {
            throw AppException.badRequest("A reason is required", ErrorCode.VALIDATION_ERROR);
        }
        Event event = loadRevisionForReview(actor, eventId);
        Event reviewed = markRevisionReviewed(event, actor, RevisionStatus.REJECTED, reviewComment(reason));
        news.audit(reviewed, AuditAction.EVENT_REVISION_REJECTED, actor, reviewed.getStatus().name(), reviewed.getRevision().getReviewComment(), null);
        notifications.notify(revisionAudience(reviewed), Notice.builder()
                .type(NotificationType.EVENT_REJECTED)
                .title("Changes to \"" + reviewed.getTitle() + "\" were not approved")
                .message(reviewed.getRevision().getReviewComment() + " The event stays as it was.")
                .link(EventNews.link(reviewed))
                .email(true)
                .build());
        return views.getEventDetail(actor, event.getId());
    }

    /** Applies approved changes to the live event and tells everyone registered what changed. */
    public Map<String, Object> publishEventChanges(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.PUBLISH_EVENTS, "You cannot publish changes to this club's events");
        if (event.getStatus() != EventStatus.PUBLISHED || event.getRevision() == null || event.getRevision().getStatus() != RevisionStatus.APPROVED) {
            throw AppException.conflict("Only changes approved by the faculty mentor can be published", ErrorCode.INVALID_STATE);
        }
        EventRules.assertInFuture(event, "This event has already started, so its details can no longer be changed");

        EventRevision revision = event.getRevision();
        Map<String, Object> changes = revision.getChanges() == null ? Map.of() : revision.getChanges();
        List<String> fields = revision.getFields();
        String venueId = truthy(changes.get("venue")) ? Values.id(changes.get("venue")) : event.getVenue();
        Venue venue = mongo.findById(venueId, Venue.class);
        if (changes.containsKey("maxParticipants")) {
            int teamSize = truthy(changes.get("maxTeamSize")) ? (int) Values.number(changes.get("maxTeamSize")) : event.getMaxTeamSize();
            EventRules.assertCapacityFits(changes.get("maxParticipants"), venue, event.getRegisteredCount(), teamSize);
        }
        if (fields.stream().anyMatch(EventRules.TEAM_FIELDS::contains) && activeRegistrations(event.getId()) > 0) {
            throw AppException.conflict("Team settings can't change once students have registered. Discard these changes and edit again.", ErrorCode.INVALID_STATE);
        }
        Object newLimit = changes.get("maxParticipants");
        boolean morePlaces = changes.containsKey("maxParticipants")
                && (newLimit == null || Values.number(newLimit) > (event.getMaxParticipants() == null ? 0 : event.getMaxParticipants()));

        venues.withVenueLock(venueId, () -> {
            assertVenueStillFree(event, changes);
            boolean applied = events.update(
                    Query.query(Criteria.where("_id").is(new ObjectId(event.getId())).and("revision.status").is(RevisionStatus.APPROVED.name())),
                    changesUpdate(changes).set("revision", null).set("updatedBy", new ObjectId(actor.id())));
            if (!applied) {
                throw AppException.conflict("Only changes approved by the faculty mentor can be published", ErrorCode.INVALID_STATE);
            }
        });
        Event updated = events.find(event.getId());
        news.audit(updated, AuditAction.EVENT_REVISION_PUBLISHED, actor, updated.getStatus().name(), null, Map.of("fields", fields));

        if (morePlaces) {
            waitlist.promoteFromWaitlist(event.getId(), "capacity_increased");
        }
        String summary = "Updated: " + EventRules.labelList(fields) + "." + (truthy(revision.getNote()) ? " " + revision.getNote() : "");
        systemPosts.create(new SystemPosts.Post(FeedPostType.EVENT_UPDATE, updated.getClub(), updated.getId(), null, actor.id(), "Event details updated", summary, null));
        news.broadcast(updated, actor, NotificationType.EVENT_UPDATED, updated.getTitle() + " has been updated", summary);
        return views.getEventDetail(actor, event.getId());
    }

    public Map<String, Object> discardEventChanges(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_EVENTS, "You cannot edit this club's events");
        if (event.getRevision() == null) {
            throw AppException.conflict("There are no pending changes to discard", ErrorCode.INVALID_STATE);
        }
        events.update(event.getId(), new Update().set("revision", null));
        news.audit(event, AuditAction.EVENT_REVISION_DISCARDED, actor, event.getStatus().name());
        return views.getEventDetail(actor, event.getId());
    }
}
