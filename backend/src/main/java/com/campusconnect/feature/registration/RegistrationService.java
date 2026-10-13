package com.campusconnect.feature.registration;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.util.Updates;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamMemberStatus;
import com.campusconnect.domain.event.TeamRole;
import com.campusconnect.domain.event.TeamStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.feature.event.EventStore;
import com.campusconnect.feature.registration.dto.RegistrationBodies;
import com.campusconnect.feature.team.Teams;
import com.campusconnect.feature.ticket.TicketService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registering for events, the participant list and a student's own registrations (services/RegistrationService.js).
 * Seats are taken with one atomic conditional update each, so concurrent registrations can never overfill an event:
 * whoever doesn't get a seat joins the waitlist, in order.
 */
@Service
public class RegistrationService {

    private final MongoOperations mongo;
    private final EventStore events;
    private final Teams teams;
    private final Waitlist waitlist;
    private final TicketService tickets;
    private final RegistrationForms forms;
    private final ScheduleClashService clashes;
    private final ClubAccess access;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Refs refs;
    private final JsonMapper json;

    public RegistrationService(MongoOperations mongo, EventStore events, Teams teams, Waitlist waitlist, TicketService tickets, RegistrationForms forms,
            ScheduleClashService clashes, ClubAccess access, NotificationService notifications, AuditService audit, Refs refs, JsonMapper json) {
        this.mongo = mongo;
        this.events = events;
        this.teams = teams;
        this.waitlist = waitlist;
        this.tickets = tickets;
        this.forms = forms;
        this.clashes = clashes;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.refs = refs;
        this.json = json;
    }

    // ---------------------------------------------------------------- rules

    /** Why this student can't register for the event, or null. */
    public static String eligibilityProblem(String departmentCode, String batchCode, Event event) {
        List<String> departments = event.getEligibility() == null ? List.of() : event.getEligibility().getDepartments();
        List<String> batches = event.getEligibility() == null ? List.of() : event.getEligibility().getBatches();
        if (!departments.isEmpty() && !departments.contains(departmentCode)) {
            return "This event is open to " + String.join(", ", departments) + " students only";
        }
        if (!batches.isEmpty() && !batches.contains(batchCode)) {
            return "This event is open to batch " + batches.stream().map(batch -> "20" + batch).collect(Collectors.joining(", ")) + " only";
        }
        return null;
    }

    static void assertCanRegister(AuthUser actor, Event event, Instant now) {
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw AppException.badRequest("Registration is only available for published events", ErrorCode.INVALID_STATE);
        }
        if (event.isRegistrationClosed()) {
            throw AppException.badRequest("Registration for this event is closed", ErrorCode.REGISTRATION_CLOSED);
        }
        if (event.getRegistrationStart() != null && now.isBefore(event.getRegistrationStart())) {
            throw AppException.badRequest("Registration has not opened yet", ErrorCode.REGISTRATION_NOT_OPEN);
        }
        if ((event.getRegistrationEnd() != null && now.isAfter(event.getRegistrationEnd())) || !now.isBefore(event.getStartAt())) {
            throw AppException.badRequest("The registration deadline has passed", ErrorCode.REGISTRATION_CLOSED);
        }
        String problem = eligibilityProblem(actor.departmentCode(), actor.batchCode(), event);
        if (problem != null) {
            throw AppException.forbidden(problem, ErrorCode.NOT_ELIGIBLE);
        }
    }

    /** Events of a suspended or archived club are on hold: no new registrations until it's reactivated. */
    private void assertClubRunning(Event event) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(event.getClub())));
        query.fields().include("name", "status");
        Club club = mongo.findOne(query, Club.class);
        if (club != null && club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict(club.getName() + " is " + club.getStatus().name().toLowerCase(Locale.ROOT)
                    + ", so this event is on hold. Registration reopens if the club is reactivated.", ErrorCode.INVALID_STATE);
        }
    }

    static String eventDateLabel(Event event) {
        return CampusTime.formatDateKey(CampusTime.toDateKey(event.getEventDate())) + " at " + event.getStartTime();
    }

    static String link(Event event) {
        return "/events/" + event.getId();
    }

    private EventRegistration findRegistration(String eventId, String userId) {
        return mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("user").is(new ObjectId(userId))), EventRegistration.class);
    }

    /** Who a place belongs to: a student alone, or a team (through its leader or a member). */
    record Place(String team, TeamRole teamRole, List<FormAnswer> answers) {

        Update applyTo(Update update) {
            return update.set("team", team == null ? null : new ObjectId(team)).set("teamRole", teamRole == null ? null : teamRole.name()).set("answers", answers);
        }
    }

    private static EventRegistration newRegistration(Event event, AuthUser actor, RegistrationStatus status, Place place) {
        EventRegistration registration = new EventRegistration();
        registration.setEvent(event.getId());
        registration.setUser(actor.id());
        registration.setStatus(status);
        registration.setRegisteredAt(Instant.now());
        registration.setTeam(place.team());
        registration.setTeamRole(place.teamRole());
        registration.setAnswers(new ArrayList<>(place.answers()));
        return registration;
    }

    // ---------------------------------------------------------------- registering

    /**
     * Registers a student. For team events the student registers a team as its leader (teamName, and optionally
     * invitees: students to invite); the team takes one place and teammates join by accepting.
     */
    public Map<String, Object> registerForEvent(AuthUser actor, String eventId, RegistrationBodies.Register body) {
        ClubAccess.assertVerified(actor);
        ClubAccess.assertStudent(actor, "Only students can register for events");
        Event event = events.find(eventId);
        assertCanRegister(actor, event, Instant.now());
        assertClubRunning(event);

        EventRegistration existing = findRegistration(event.getId(), actor.id());
        if (existing != null && existing.getStatus() == RegistrationStatus.REGISTERED) {
            throw AppException.conflict("You are already registered for this event", ErrorCode.DUPLICATE_REGISTRATION);
        }
        if (existing != null && existing.getStatus() == RegistrationStatus.WAITLISTED) {
            throw AppException.conflict("You are already on the waitlist (#" + waitlist.waitlistPosition(existing) + ")", ErrorCode.DUPLICATE_REGISTRATION);
        }

        if (event.getParticipationMode() != ParticipationMode.TEAM) {
            RegistrationForms.Answers given = forms.answersFor(event, null, body.answers(), body.teamAnswers());
            // Same time as another event: refused, or switched when the student agreed (replace).
            List<Map<String, Object>> switchedFrom = clashes.resolveClashes(actor, event, body.replace());
            Map<String, Object> result = takePlace(actor, event, existing, new Place(null, null, given.answers()), null);
            if (!switchedFrom.isEmpty()) {
                result.put("switchedFrom", switchedFrom);
            }
            return result;
        }

        RegistrationForms.Answers given = forms.answersFor(event, TeamRole.LEADER, body.answers(), body.teamAnswers());
        // Checked before the team exists, so a refused switch leaves nothing behind.
        clashes.assertResolvable(actor, event, body.replace());
        Teams.Created created = teams.createTeam(actor, event, body.teamName(), body.invitees(), given.teamAnswers());
        Team team = created.team();
        Map<String, Object> result;
        List<Map<String, Object>> switchedFrom;
        try {
            switchedFrom = clashes.resolveClashes(actor, event, body.replace());
            result = takePlace(actor, event, existing, new Place(team.getId(), TeamRole.LEADER, given.answers()), team);
        } catch (RuntimeException e) {
            teams.discardTeam(team);
            throw e;
        }
        teams.afterTeamCreated(team, event, actor, created.invitees());
        result.put("team", teams.getTeamView(team.getId(), event));
        if (!switchedFrom.isEmpty()) {
            result.put("switchedFrom", switchedFrom);
        }
        return result;
    }

    /** Takes a place (or a waitlist spot) for one registration: a student, or a team through its leader. */
    private Map<String, Object> takePlace(AuthUser actor, Event event, EventRegistration existing, Place place, Team team) {
        // Students already waiting keep their place: a newcomer only gets a seat directly when nobody is queued.
        boolean queueExists = mongo.exists(Query.query(Criteria.where("event").is(new ObjectId(event.getId()))
                .and("status").is(RegistrationStatus.WAITLISTED.name())), EventRegistration.class);
        // Reserve a seat first: the conditional $inc is atomic, so concurrent registrations cannot overfill.
        Event reserved = queueExists ? null : waitlist.reserveSeat(event);
        if (reserved == null) {
            return joinWaitlist(actor, event, existing, place);
        }

        EventRegistration registration;
        try {
            // A place always comes with a fresh ticket (a re-registration invalidates the old QR).
            registration = tickets.withTicketRetry(ticket -> existing != null
                    // The unique (event, user) index plus the status guard stop two parallel re-registrations.
                    ? mongo.findAndModify(
                            Query.query(Criteria.where("_id").is(new ObjectId(existing.getId())).and("status").is(RegistrationStatus.CANCELLED.name())),
                            Updates.touch(place.applyTo(ticket.applyTo(new Update().set("status", RegistrationStatus.REGISTERED.name())
                                    .set("registeredAt", Instant.now()).set("waitlistedAt", null).set("promotedAt", null)))),
                            FindAndModifyOptions.options().returnNew(true), EventRegistration.class)
                    : mongo.insert(ticket.applyTo(newRegistration(event, actor, RegistrationStatus.REGISTERED, place))));
            if (registration == null) {
                throw AppException.conflict("You are already registered for this event", ErrorCode.DUPLICATE_REGISTRATION);
            }
        } catch (DuplicateKeyException e) {
            waitlist.releaseSeat(event.getId());
            throw AppException.conflict("You are already registered for this event", ErrorCode.DUPLICATE_REGISTRATION);
        } catch (RuntimeException e) {
            waitlist.releaseSeat(event.getId());
            throw e;
        }

        audit.record(AuditAction.STUDENT_REGISTERED, actor.id(), "EventRegistration", registration.getId(), null, RegistrationStatus.REGISTERED.name(), null,
                Map.of("eventId", event.getId()));
        int minimum = event.getMinTeamSize();
        notifications.notify(actor.id(), Notice.builder()
                .type(NotificationType.REGISTRATION_CONFIRMED)
                .title(team != null ? "\"" + team.getName() + "\" is registered for " + event.getTitle() : "You're registered for " + event.getTitle())
                .message(team != null
                        ? "You're the team leader. Your teammates join by accepting your invites; teams need at least " + minimum + " member" + (minimum == 1 ? "" : "s")
                                + ". See you on " + eventDateLabel(event) + "."
                        : "See you on " + eventDateLabel(event) + ". Your ticket is ready — show its QR code at the entrance.")
                .link(link(event))
                .build());
        // The ticket (QR + code) goes by e-mail instead of a plain confirmation.
        tickets.sendTicketEmail(registration.getId(), team != null ? "team" : "registered");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registration", registration);
        result.put("waitlisted", false);
        result.put("registeredCount", reserved.getRegisteredCount());
        result.put("maxParticipants", reserved.getMaxParticipants());
        result.put("waitlistCount", reserved.getWaitlistCount());
        return result;
    }

    /** Puts the student (or team) in the queue for a full event, re-using a cancelled registration if there is one. */
    private Map<String, Object> joinWaitlist(AuthUser actor, Event event, EventRegistration existing, Place place) {
        Instant now = Instant.now();
        EventRegistration registration;
        try {
            if (existing != null) {
                registration = mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(new ObjectId(existing.getId())).and("status").is(RegistrationStatus.CANCELLED.name())),
                        Updates.touch(place.applyTo(new Update().set("status", RegistrationStatus.WAITLISTED.name()).set("waitlistedAt", now).set("promotedAt", null))),
                        FindAndModifyOptions.options().returnNew(true), EventRegistration.class);
            } else {
                EventRegistration fresh = newRegistration(event, actor, RegistrationStatus.WAITLISTED, place);
                fresh.setWaitlistedAt(now);
                registration = mongo.insert(fresh);
            }
        } catch (DuplicateKeyException e) {
            throw AppException.conflict("You are already registered or on the waitlist for this event", ErrorCode.DUPLICATE_REGISTRATION);
        }
        if (registration == null) {
            throw AppException.conflict("You are already registered or on the waitlist for this event", ErrorCode.DUPLICATE_REGISTRATION);
        }

        waitlist.adjustWaitlistCount(event.getId(), 1);
        audit.record(AuditAction.WAITLIST_JOINED, actor.id(), "EventRegistration", registration.getId(), null, RegistrationStatus.WAITLISTED.name(), null,
                Map.of("eventId", event.getId()));

        // A seat may have been freed between our failed reservation and joining the queue; fill it now.
        waitlist.promoteFromWaitlist(event.getId(), "joined_while_seat_free");

        EventRegistration current = mongo.findById(registration.getId(), EventRegistration.class);
        Map<String, Object> result = new LinkedHashMap<>();
        if (current.getStatus() == RegistrationStatus.REGISTERED) {
            result.put("registration", current);
            result.put("waitlisted", false);
            result.putAll(waitlist.counts(event.getId()));
            return result;
        }

        Integer position = waitlist.waitlistPosition(current);
        boolean asTeam = place.team() != null;
        notifications.notify(actor.id(), Notice.builder()
                .type(NotificationType.WAITLISTED)
                .title((asTeam ? "Your team is" : "You're") + " #" + position + " on the waitlist for " + event.getTitle())
                .message(asTeam
                        ? "The event is full. If a place frees up your whole team is registered automatically and we'll email everyone."
                        : "The event is full. If a seat frees up you'll be registered automatically and we'll email you.")
                .link(link(event))
                .email(true)
                .build());

        result.put("registration", current);
        result.put("waitlisted", true);
        result.put("waitlistPosition", position);
        result.putAll(waitlist.counts(event.getId()));
        return result;
    }

    // ---------------------------------------------------------------- participants (organisers)

    /** The participants page: registrations, the waitlist in order, teams and the form's answer columns. */
    public record Participants(List<Map<String, Object>> items, Map<String, Object> meta) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> plain(EventRegistration registration) {
        return json.convertValue(registration, LinkedHashMap.class);
    }

    private static Map<String, Object> pick(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", source.get("_id"));
        for (String key : keys) {
            view.put(key, source.get(key));
        }
        return view;
    }

    public Participants listParticipants(AuthUser actor, String eventId, String search) {
        Event event = events.find(eventId);
        access.assertCanViewParticipants(actor, event.getClub(), ClubPermission.VIEW_PARTICIPANTS);

        ObjectId id = new ObjectId(event.getId());
        List<EventRegistration> registrations = mongo.find(Query.query(Criteria.where("event").is(id).and("status").is(RegistrationStatus.REGISTERED.name()))
                .with(Sort.by("registeredAt")), EventRegistration.class);
        // The waitlist lists places in the queue: students, or teams through their leader.
        List<EventRegistration> queued = mongo.find(Query.query(Waitlist.queueEntry(event.getId())).with(Waitlist.queueOrder()), EventRegistration.class);

        List<EventRegistration> all = Stream.concat(registrations.stream(), queued.stream()).toList();
        Map<String, Map<String, Object>> users = refs.users(all.stream().map(EventRegistration::getUser).toList(), "name", "email", "departmentCode", "batchCode", "avatar");
        Map<String, Map<String, Object>> checkers = refs.users(registrations.stream().map(EventRegistration::getCheckedInBy).filter(Objects::nonNull).toList(), "name");
        List<String> teamIds = all.stream().map(EventRegistration::getTeam).filter(Objects::nonNull).distinct().toList();
        Map<String, Team> teamDocs = teamIds.isEmpty() ? Map.of() : mongo.find(Query.query(Criteria.where("_id").in(teamIds.stream().map(ObjectId::new).toList())), Team.class)
                .stream().collect(Collectors.toMap(Team::getId, Function.identity()));

        List<Map<String, Object>> items = new ArrayList<>();
        for (EventRegistration registration : registrations) {
            if (!users.containsKey(registration.getUser())) {
                continue;
            }
            Team team = registration.getTeam() == null ? null : teamDocs.get(registration.getTeam());
            Map<String, Object> row = plain(registration);
            row.remove("answers");
            row.put("user", users.get(registration.getUser()));
            row.put("team", teamRef(team));
            row.put("checkedInBy", registration.getCheckedInBy() == null ? null : checkers.get(registration.getCheckedInBy()));
            row.put("formAnswers", forms.answerCells(event, registration, team == null ? null : team.getAnswers()));
            items.add(row);
        }
        List<Map<String, Object>> queue = new ArrayList<>();
        for (EventRegistration registration : queued) {
            if (!users.containsKey(registration.getUser())) {
                continue;
            }
            Map<String, Object> row = plain(registration);
            row.put("user", users.get(registration.getUser()));
            row.put("team", teamRef(registration.getTeam() == null ? null : teamDocs.get(registration.getTeam())));
            row.put("position", queue.size() + 1);
            queue.add(row);
        }

        if (!Texts.isBlank(search)) {
            Pattern pattern = Pattern.compile(Texts.searchPattern(search), Pattern.CASE_INSENSITIVE);
            java.util.function.Predicate<Map<String, Object>> matches = row -> {
                Map<?, ?> user = (Map<?, ?>) row.get("user");
                return pattern.matcher(String.valueOf(user.get("name"))).find() || pattern.matcher(String.valueOf(user.get("email"))).find();
            };
            items = items.stream().filter(matches).toList();
            queue = queue.stream().filter(matches).toList();
        }

        List<Map<String, Object>> teamList = List.of();
        if (event.getParticipationMode() == ParticipationMode.TEAM) {
            List<Team> active = mongo.find(Query.query(Criteria.where("event").is(id).and("status").is(TeamStatus.ACTIVE.name())).with(Sort.by("createdAt")), Team.class);
            teamList = teams.getTeamViews(active, event);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("_id", event.getId());
        summary.put("title", event.getTitle());
        summary.put("status", event.getStatus());
        summary.put("registeredCount", event.getRegisteredCount());
        summary.put("maxParticipants", event.getMaxParticipants());
        summary.put("waitlistCount", event.getWaitlistCount());
        summary.put("attendedCount", items.stream().filter(row -> row.get("checkedInAt") != null).count());
        summary.put("checkIn", event.getCheckIn());
        summary.put("participationMode", event.getParticipationMode());
        summary.put("minTeamSize", event.getMinTeamSize());
        summary.put("maxTeamSize", event.getMaxTeamSize());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", items.size());
        meta.put("event", summary);
        meta.put("waitlist", queue);
        meta.put("teams", teamList);
        meta.put("formColumns", forms.answerColumns(event));
        return new Participants(items, meta);
    }

    private static Map<String, Object> teamRef(Team team) {
        if (team == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", team.getId());
        view.put("name", team.getName());
        view.put("size", team.getSize());
        return view;
    }

    public void removeParticipant(AuthUser actor, String eventId, String registrationId, String reason) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_PARTICIPANTS, "You cannot manage participants for this club");
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw AppException.conflict("Participants can only be removed from upcoming published events", ErrorCode.INVALID_STATE);
        }

        EventRegistration registration = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(registrationId)).and("event").is(new ObjectId(event.getId())).and("status").in(Teams.ACTIVE)),
                Updates.set("status", RegistrationStatus.CANCELLED.name()), FindAndModifyOptions.options().returnNew(false), EventRegistration.class);
        if (registration == null) {
            throw AppException.notFound("Registration not found");
        }
        String reasonPrefix = reason != null && !reason.isEmpty() ? reason + " " : "";

        if (registration.getTeamRole() == TeamRole.MEMBER) {
            Team team = mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(new ObjectId(registration.getTeam()))
                            .and("members").elemMatch(Criteria.where("user").is(new ObjectId(registration.getUser())).and("status").is(TeamMemberStatus.ACCEPTED.name()))),
                    Updates.touch(new Update().set("members.$.status", TeamMemberStatus.REMOVED.name()).set("members.$.respondedAt", Instant.now()).inc("size", -1)),
                    FindAndModifyOptions.options().returnNew(true), Team.class);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("eventId", event.getId());
            metadata.put("userId", registration.getUser());
            metadata.put("teamId", registration.getTeam());
            audit.record(AuditAction.PARTICIPANT_REMOVED, actor.id(), "EventRegistration", registration.getId(), registration.getStatus().name(),
                    RegistrationStatus.CANCELLED.name(), reason, metadata);
            notifications.notify(registration.getUser(), Notice.builder()
                    .type(NotificationType.REGISTRATION_REMOVED)
                    .title("The organisers removed you from " + (team != null ? "\"" + team.getName() + "\" for " : "") + event.getTitle())
                    .message(reasonPrefix + "Your ticket is no longer valid.")
                    .link(link(event))
                    .email(true)
                    .build());
            if (team != null) {
                notifications.notify(team.getLeader(), Notice.builder()
                        .type(NotificationType.TEAM_UPDATE)
                        .title("The organisers removed a member from \"" + team.getName() + "\"")
                        .message(reason == null ? "" : reason)
                        .link(link(event))
                        .build());
            }
            return;
        }

        boolean wasWaitlisted = registration.getStatus() == RegistrationStatus.WAITLISTED;
        if (wasWaitlisted) {
            waitlist.adjustWaitlistCount(event.getId(), -1);
        } else {
            waitlist.releaseSeat(event.getId());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.getId());
        metadata.put("userId", registration.getUser());
        audit.record(AuditAction.PARTICIPANT_REMOVED, actor.id(), "EventRegistration", registration.getId(), registration.getStatus().name(),
                RegistrationStatus.CANCELLED.name(), reason, metadata);
        if (registration.getTeamRole() == TeamRole.LEADER) {
            teams.disbandTeam(registration.getTeam(), event, actor, true, reason);
        }
        if (!wasWaitlisted) {
            waitlist.promoteFromWaitlist(event.getId(), "participant_removed");
        }
        notifications.notify(registration.getUser(), Notice.builder()
                .type(NotificationType.REGISTRATION_REMOVED)
                .title("Your registration for " + event.getTitle() + " was cancelled by the organisers")
                .message(reasonPrefix + "Your ticket is no longer valid.")
                .link(link(event))
                .email(true)
                .build());
    }

    // ---------------------------------------------------------------- public count and "my registrations"

    public Map<String, Object> getPublicRegistrationCount(String eventId) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(eventId)));
        query.fields().include("registeredCount", "maxParticipants", "status");
        Event event = mongo.findOne(query, Event.class);
        if (event == null || !EventStatus.PUBLIC.contains(event.getStatus())) {
            throw AppException.notFound("Event not found");
        }
        Map<String, Object> count = new LinkedHashMap<>();
        count.put("registeredCount", event.getRegisteredCount());
        count.put("maxParticipants", event.getMaxParticipants());
        return count;
    }

    /** The student's registrations (newest first); includeWaitlist=true adds waitlisted events with the place in the queue. */
    public List<Map<String, Object>> getMyRegistrations(AuthUser actor, String timeframe, String includeWaitlist) {
        boolean withWaitlist = "true".equals(includeWaitlist);
        List<String> statuses = withWaitlist ? Teams.ACTIVE : List.of(RegistrationStatus.REGISTERED.name());
        List<EventRegistration> registrations = mongo.find(Query.query(Criteria.where("user").is(new ObjectId(actor.id())).and("status").in(statuses))
                .with(Sort.by(Sort.Direction.DESC, "registeredAt")), EventRegistration.class);

        Map<String, Map<String, Object>> eventRefs = refs.events(registrations.stream().map(EventRegistration::getEvent).toList(),
                "title", "shortDescription", "poster", "startAt", "endAt", "startTime", "endTime", "status", "club", "venue", "registeredCount", "maxParticipants",
                "category", "participationMode", "minTeamSize", "maxTeamSize");
        Map<String, Map<String, Object>> clubs = refs.clubs(eventRefs.values().stream().map(row -> (String) row.get("club")).toList(), "name", "logo");
        Map<String, Map<String, Object>> venues = refs.load("venues", eventRefs.values().stream().map(row -> (String) row.get("venue")).toList(), "name", "location");
        Map<String, Map<String, Object>> teamRefs = refs.load("teams", registrations.stream().map(EventRegistration::getTeam).filter(Objects::nonNull).toList(), "name", "size");

        Instant now = Instant.now();
        List<EventRegistration> items = new ArrayList<>(registrations.stream().filter(row -> eventRefs.containsKey(row.getEvent())).toList());
        if ("upcoming".equals(timeframe)) {
            items = new ArrayList<>(items.stream().filter(row -> isAfter(eventRefs.get(row.getEvent()).get("endAt"), now)
                    && EventStatus.PUBLISHED.name().equals(eventRefs.get(row.getEvent()).get("status"))).toList());
            items.sort(Comparator.comparing(row -> (Instant) eventRefs.get(row.getEvent()).get("startAt"), Comparator.nullsLast(Comparator.naturalOrder())));
        } else if ("past".equals(timeframe)) {
            items = new ArrayList<>(items.stream().filter(row -> !isAfter(eventRefs.get(row.getEvent()).get("endAt"), now)
                    || !EventStatus.PUBLISHED.name().equals(eventRefs.get(row.getEvent()).get("status"))).toList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (EventRegistration registration : items) {
            Map<String, Object> event = new LinkedHashMap<>(eventRefs.get(registration.getEvent()));
            event.put("club", clubs.get((String) event.get("club")));
            event.put("venue", venues.get((String) event.get("venue")));
            Map<String, Object> row = plain(registration);
            row.put("event", event);
            row.put("team", registration.getTeam() == null ? null : teamRefs.get(registration.getTeam()));
            if (withWaitlist) {
                row.put("waitlistPosition", waitlist.waitlistPosition(registration));
            }
            result.add(row);
        }
        return result;
    }

    private static boolean isAfter(Object value, Instant now) {
        return value instanceof Instant instant && instant.isAfter(now);
    }
}
