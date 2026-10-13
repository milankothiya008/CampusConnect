package com.campusconnect.feature.team;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.util.Updates;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamMember;
import com.campusconnect.domain.event.TeamMemberStatus;
import com.campusconnect.domain.event.TeamRole;
import com.campusconnect.domain.event.TeamStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.event.EventStore;
import com.campusconnect.feature.registration.RegistrationForms;
import com.campusconnect.feature.registration.ScheduleClashService;
import com.campusconnect.feature.ticket.TicketService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Team registration, the way Unstop does it (services/TeamService.js): the leader invites and manages teammates;
 * each invitee accepts or declines. Joining claims a spot on the team with one conditional update, so two people
 * accepting at once can never overfill it.
 */
@Service
public class TeamService {

    private final MongoOperations mongo;
    private final EventStore events;
    private final Teams teams;
    private final RegistrationForms forms;
    private final ScheduleClashService clashes;
    private final TicketService tickets;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Refs refs;

    public TeamService(MongoOperations mongo, EventStore events, Teams teams, RegistrationForms forms, ScheduleClashService clashes, TicketService tickets,
            NotificationService notifications, AuditService audit, Refs refs) {
        this.mongo = mongo;
        this.events = events;
        this.teams = teams;
        this.forms = forms;
        this.clashes = clashes;
        this.tickets = tickets;
        this.notifications = notifications;
        this.audit = audit;
        this.refs = refs;
    }

    private static AppException conflict(String message) {
        return AppException.conflict(message, ErrorCode.INVALID_STATE);
    }

    private Event findTeamEvent(String eventId) {
        Event event = events.find(eventId);
        if (event.getParticipationMode() != ParticipationMode.TEAM) {
            throw AppException.badRequest("This event takes individual registrations, not teams", ErrorCode.VALIDATION_ERROR);
        }
        return event;
    }

    /** Teams can take shape until registration closes (closing new sign-ups early doesn't stop existing teams filling up). */
    static void assertTeamsOpen(Event event) {
        Instant now = Instant.now();
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw conflict("Teams can only change for published events");
        }
        if ((event.getRegistrationEnd() != null && now.isAfter(event.getRegistrationEnd())) || !now.isBefore(event.getStartAt())) {
            throw conflict("Registration has closed, so teams can no longer change");
        }
    }

    private Team loadLeaderTeam(AuthUser actor, Event event) {
        Team team = mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("leader").is(new ObjectId(actor.id()))
                .and("status").is(TeamStatus.ACTIVE.name())), Team.class);
        if (team == null) {
            throw AppException.forbidden("Only the team leader can manage the team", ErrorCode.FORBIDDEN);
        }
        return team;
    }

    public Map<String, Object> inviteMembers(AuthUser actor, String eventId, List<String> userIds) {
        Event event = findTeamEvent(eventId);
        assertTeamsOpen(event);
        Team team = loadLeaderTeam(actor, event);
        List<Teams.Invitee> users = teams.checkInvitees(event, actor.id(), userIds, team);
        if (users.isEmpty()) {
            throw AppException.badRequest("Choose at least one student to invite", ErrorCode.VALIDATION_ERROR);
        }

        Instant now = Instant.now();
        Set<String> listed = new HashSet<>();
        team.getMembers().forEach(member -> listed.add(member.getUser()));
        List<ObjectId> again = users.stream().map(Teams.Invitee::id).filter(listed::contains).map(ObjectId::new).toList();
        List<TeamMember> fresh = users.stream().filter(user -> !listed.contains(user.id())).map(user -> Teams.member(user.id(), TeamMemberStatus.INVITED, now, null)).toList();
        // Students who answered an earlier invite are invited again in place; new ones are added to the roster.
        if (!again.isEmpty()) {
            mongo.updateFirst(Teams.byId(team.getId()), Updates.touch(new Update()
                    .set("members.$[again].status", TeamMemberStatus.INVITED.name())
                    .set("members.$[again].invitedAt", now)
                    .set("members.$[again].respondedAt", null)
                    .filterArray(Criteria.where("again.user").in(again))), Team.class);
        }
        if (!fresh.isEmpty()) {
            mongo.updateFirst(Teams.byId(team.getId()), Updates.touch(new Update().push("members").each(fresh.toArray())), Team.class);
        }
        teams.sendInvites(team, event, actor, users);
        return teams.getTeamView(team.getId(), event);
    }

    /** Removes a member (or withdraws an invite). Removing an accepted member cancels their registration. */
    public Map<String, Object> removeMember(AuthUser actor, String eventId, String userId) {
        Event event = findTeamEvent(eventId);
        assertTeamsOpen(event);
        Team team = loadLeaderTeam(actor, event);
        if (userId.equals(actor.id())) {
            throw AppException.badRequest("To leave, cancel the team's registration instead", ErrorCode.VALIDATION_ERROR);
        }
        TeamMember member = team.getMembers().stream()
                .filter(entry -> userId.equals(entry.getUser()) && (entry.getStatus() == TeamMemberStatus.INVITED || entry.getStatus() == TeamMemberStatus.ACCEPTED))
                .findFirst().orElse(null);
        if (member == null) {
            throw AppException.notFound("That student isn't on your team");
        }

        boolean wasMember = member.getStatus() == TeamMemberStatus.ACCEPTED;
        Update update = new Update()
                .set("members.$.status", (wasMember ? TeamMemberStatus.REMOVED : TeamMemberStatus.CANCELLED).name())
                .set("members.$.respondedAt", Instant.now());
        if (wasMember) {
            update.inc("size", -1);
        }
        Team updated = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(team.getId()))
                        .and("members").elemMatch(Criteria.where("user").is(new ObjectId(member.getUser())).and("status").is(member.getStatus().name()))),
                Updates.touch(update), FindAndModifyOptions.options().returnNew(true), Team.class);
        if (updated == null) {
            throw conflict("The team changed in the meantime — refresh and try again");
        }

        if (wasMember) {
            mongo.updateFirst(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").is(new ObjectId(member.getUser()))
                            .and("team").is(new ObjectId(team.getId())).and("status").in(Teams.ACTIVE)),
                    Updates.set("status", RegistrationStatus.CANCELLED.name()), EventRegistration.class);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("eventId", event.getId());
            metadata.put("userId", member.getUser());
            audit.record(AuditAction.TEAM_MEMBER_REMOVED, actor.id(), "Team", team.getId(), metadata);
            notifications.notify(member.getUser(), Notice.builder()
                    .type(NotificationType.TEAM_UPDATE)
                    .title("You were removed from \"" + team.getName() + "\" for " + event.getTitle())
                    .message("Your registration through the team was cancelled and your ticket is no longer valid. You can register again on your own or with another team while registration is open.")
                    .link(Teams.eventLink(event))
                    .email(true)
                    .emailCategory(EmailCategory.EVENT_ACTIVITY)
                    .build());
        }
        return teams.getTeamView(team.getId(), event);
    }

    /** The invitee's answer. Accepting registers them with the team's status (registered or waitlisted). */
    public Map<String, Object> respondToInvite(AuthUser actor, String eventId, String teamId, boolean accept, Object answers, Collection<String> replace) {
        ClubAccess.assertVerified(actor);
        ClubAccess.assertStudent(actor, "Only students can join teams");
        Event event = findTeamEvent(eventId);
        Team team = mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(teamId)).and("event").is(new ObjectId(event.getId()))), Team.class);
        boolean invited = team != null && team.getMembers().stream()
                .anyMatch(member -> actor.id().equals(member.getUser()) && member.getStatus() == TeamMemberStatus.INVITED);
        if (team == null || team.getStatus() != TeamStatus.ACTIVE || !invited) {
            throw AppException.notFound("This invite is no longer available");
        }
        Map<String, Object> leader = refs.user(team.getLeader(), "name");

        if (!accept) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(team.getId()))
                            .and("members").elemMatch(Criteria.where("user").is(new ObjectId(actor.id())).and("status").is(TeamMemberStatus.INVITED.name()))),
                    Updates.touch(new Update().set("members.$.status", TeamMemberStatus.DECLINED.name()).set("members.$.respondedAt", Instant.now())), Team.class);
            audit.record(AuditAction.TEAM_INVITE_DECLINED, actor.id(), "Team", team.getId(), Map.of("eventId", event.getId()));
            notifications.notify(team.getLeader(), Notice.builder()
                    .type(NotificationType.TEAM_UPDATE)
                    .title(actor.name() + " declined your invite to \"" + team.getName() + "\"")
                    .message("Invite someone else for " + event.getTitle() + " while registration is open.")
                    .link(Teams.eventLink(event))
                    .build());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accepted", false);
            result.put("team", teams.getTeamView(team.getId(), event));
            return result;
        }

        assertTeamsOpen(event);
        String problem = Teams.eligibilityProblem(actor.departmentCode(), actor.batchCode(), event);
        if (problem != null) {
            throw AppException.forbidden("This event is " + problem, ErrorCode.NOT_ELIGIBLE);
        }
        EventRegistration existing = mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").is(new ObjectId(actor.id()))),
                EventRegistration.class);
        if (existing != null && (existing.getStatus() == RegistrationStatus.REGISTERED || existing.getStatus() == RegistrationStatus.WAITLISTED)) {
            throw AppException.conflict("You're already registered for this event. Cancel that registration first to join this team.", ErrorCode.DUPLICATE_REGISTRATION);
        }

        // Every member answers the registration form's per-member questions when joining.
        RegistrationForms.Answers given = forms.answersFor(event, TeamRole.MEMBER, answers, null);
        EventRegistration leaderRegistration = mongo.findOne(Query.query(Criteria.where("team").is(new ObjectId(team.getId()))
                .and("teamRole").is(TeamRole.LEADER.name()).and("status").in(Teams.ACTIVE)), EventRegistration.class);
        if (leaderRegistration == null) {
            throw AppException.conflict("This team is no longer registered", ErrorCode.INVALID_STATE);
        }
        // Busy at the same time: refused, or switched when the student agreed (replace).
        List<Map<String, Object>> switchedFrom = clashes.resolveClashes(actor, event, replace);

        // Claim a spot on the team atomically, so two people accepting at once can't overfill it.
        Instant now = Instant.now();
        Team claimed = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(team.getId())).and("status").is(TeamStatus.ACTIVE.name()).and("size").lt(event.getMaxTeamSize())
                        .and("members").elemMatch(Criteria.where("user").is(new ObjectId(actor.id())).and("status").is(TeamMemberStatus.INVITED.name()))),
                Updates.touch(new Update().set("members.$.status", TeamMemberStatus.ACCEPTED.name()).set("members.$.respondedAt", now).inc("size", 1)),
                FindAndModifyOptions.options().returnNew(true), Team.class);
        if (claimed == null) {
            throw conflict("\"" + team.getName() + "\" is already full");
        }

        EventRegistration registration;
        try {
            RegistrationStatus status = leaderRegistration.getStatus();
            Instant waitlistedAt = status == RegistrationStatus.WAITLISTED ? leaderRegistration.getWaitlistedAt() : null;
            // Joining a registered team issues the member's own ticket; a waitlisted team's members get theirs on promotion.
            boolean issueTicket = status == RegistrationStatus.REGISTERED;
            registration = tickets.withTicketRetry(ticket -> {
                if (existing != null) {
                    Update update = new Update().set("status", status.name()).set("team", new ObjectId(team.getId())).set("teamRole", TeamRole.MEMBER.name())
                            .set("answers", given.answers()).set("registeredAt", now).set("waitlistedAt", waitlistedAt).set("promotedAt", null);
                    return mongo.findAndModify(
                            Query.query(Criteria.where("_id").is(new ObjectId(existing.getId())).and("status").is(RegistrationStatus.CANCELLED.name())),
                            Updates.touch(issueTicket ? ticket.applyTo(update) : update), FindAndModifyOptions.options().returnNew(true), EventRegistration.class);
                }
                EventRegistration fresh = new EventRegistration();
                fresh.setEvent(event.getId());
                fresh.setUser(actor.id());
                fresh.setStatus(status);
                fresh.setTeam(team.getId());
                fresh.setTeamRole(TeamRole.MEMBER);
                fresh.setAnswers(new ArrayList<>(given.answers()));
                fresh.setRegisteredAt(now);
                fresh.setWaitlistedAt(waitlistedAt);
                return mongo.insert(issueTicket ? ticket.applyTo(fresh) : fresh);
            });
            if (registration == null) {
                throw AppException.conflict("You're already registered for this event", ErrorCode.DUPLICATE_REGISTRATION);
            }
        } catch (RuntimeException e) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(team.getId()))
                            .and("members").elemMatch(Criteria.where("user").is(new ObjectId(actor.id())).and("status").is(TeamMemberStatus.ACCEPTED.name()))),
                    Updates.touch(new Update().set("members.$.status", TeamMemberStatus.INVITED.name()).set("members.$.respondedAt", null).inc("size", -1)), Team.class);
            if (e instanceof DuplicateKeyException) {
                throw AppException.conflict("You're already registered for this event", ErrorCode.DUPLICATE_REGISTRATION);
            }
            throw e;
        }

        // One team per event: other invites for the same event are withdrawn.
        Query others = Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("_id").ne(new ObjectId(team.getId())).and("status").is(TeamStatus.ACTIVE.name())
                .and("members").elemMatch(Criteria.where("user").is(new ObjectId(actor.id())).and("status").is(TeamMemberStatus.INVITED.name())));
        others.fields().include("_id");
        List<ObjectId> otherTeams = mongo.find(others, Team.class).stream().map(other -> new ObjectId(other.getId())).toList();
        if (!otherTeams.isEmpty()) {
            mongo.updateMulti(Query.query(Criteria.where("_id").in(otherTeams)
                            .and("members").elemMatch(Criteria.where("user").is(new ObjectId(actor.id())).and("status").is(TeamMemberStatus.INVITED.name()))),
                    Updates.touch(new Update().set("members.$.status", TeamMemberStatus.DECLINED.name()).set("members.$.respondedAt", now)), Team.class);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.getId());
        metadata.put("registrationId", registration.getId());
        audit.record(AuditAction.TEAM_INVITE_ACCEPTED, actor.id(), "Team", team.getId(), metadata);

        boolean complete = claimed.getSize() >= event.getMinTeamSize();
        notifications.notify(team.getLeader(), Notice.builder()
                .type(NotificationType.TEAM_UPDATE)
                .title(actor.name() + " joined \"" + team.getName() + "\"")
                .message(complete ? "Your team now has " + claimed.getSize() + " members and is complete."
                        : "Your team has " + claimed.getSize() + " of the " + event.getMinTeamSize() + " members it needs.")
                .link(Teams.eventLink(event))
                .build());
        boolean waitlisted = registration.getStatus() == RegistrationStatus.WAITLISTED;
        String leaderName = leader != null && leader.get("name") != null ? String.valueOf(leader.get("name")) : "Your team leader";
        notifications.notify(actor.id(), Notice.builder()
                .type(NotificationType.REGISTRATION_CONFIRMED)
                .title(waitlisted ? "You joined \"" + team.getName() + "\" — the team is on the waitlist for " + event.getTitle()
                        : "You're registered for " + event.getTitle() + " with \"" + team.getName() + "\"")
                .message(waitlisted ? "If a place opens up, the whole team is registered automatically and we'll email you."
                        : leaderName + " leads the team. See you on " + CampusTime.formatDateKey(CampusTime.toDateKey(event.getEventDate())) + " at " + event.getStartTime()
                                + ". Your ticket is ready — show its QR code at the entrance.")
                .link(Teams.eventLink(event))
                .email(waitlisted)
                .build());
        if (!waitlisted) {
            tickets.sendTicketEmail(registration.getId(), "team");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("waitlisted", waitlisted);
        result.put("registration", registration);
        result.put("team", teams.getTeamView(team.getId(), event));
        if (!switchedFrom.isEmpty()) {
            result.put("switchedFrom", switchedFrom);
        }
        return result;
    }

    /** Students the leader can invite: verified, eligible, and not yet registered for the event (at most 10). */
    public List<Map<String, Object>> searchCandidates(AuthUser actor, String eventId, String search) {
        Event event = findTeamEvent(eventId);
        String text = search == null ? "" : search.strip();
        if (text.length() < 2) {
            return List.of();
        }
        String escaped = Texts.escapeRegex(text);
        String pattern = escaped.length() > 60 ? escaped.substring(0, 60) : escaped;
        Criteria filter = Criteria.where("_id").ne(new ObjectId(actor.id()))
                .and("globalRole").is(GlobalRole.STUDENT.name())
                .and("accountType").is(AccountType.STUDENT.name())
                .and("isEmailVerified").is(true)
                .and("isActive").ne(false)
                .orOperator(Criteria.where("name").regex(pattern, "i"), Criteria.where("email").regex(pattern, "i"));
        if (event.getEligibility() != null && !event.getEligibility().getDepartments().isEmpty()) {
            filter = filter.and("departmentCode").in(event.getEligibility().getDepartments());
        }
        if (event.getEligibility() != null && !event.getEligibility().getBatches().isEmpty()) {
            filter = filter.and("batchCode").in(event.getEligibility().getBatches());
        }
        Query query = Query.query(filter).with(Sort.by("name")).limit(20);
        query.fields().include("name", "email", "departmentCode", "batchCode", "avatar");
        List<User> users = mongo.find(query, User.class);
        if (users.isEmpty()) {
            return List.of();
        }
        List<ObjectId> ids = users.stream().map(user -> new ObjectId(user.getId())).toList();

        Query takenQuery = Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").in(ids).and("status").in(Teams.ACTIVE));
        takenQuery.fields().include("user");
        Set<String> taken = new HashSet<>();
        mongo.find(takenQuery, EventRegistration.class).forEach(row -> taken.add(row.getUser()));

        // Students with another event at the same time can still be invited; they'll be asked to switch when accepting.
        Map<String, String> busy = new HashMap<>();
        Query othersQuery = Query.query(Criteria.where("user").in(ids).and("status").in(Teams.ACTIVE).and("event").ne(new ObjectId(event.getId())));
        othersQuery.fields().include("user", "event");
        List<EventRegistration> others = mongo.find(othersQuery, EventRegistration.class);
        if (!others.isEmpty()) {
            Query overlapping = Query.query(Criteria.where("_id").in(others.stream().map(row -> new ObjectId(row.getEvent())).distinct().toList())
                    .and("status").is(EventStatus.PUBLISHED.name()).and("startAt").lt(event.getEndAt()).and("endAt").gt(event.getStartAt()));
            overlapping.fields().include("title");
            Map<String, String> titles = new HashMap<>();
            mongo.find(overlapping, Event.class).forEach(other -> titles.put(other.getId(), other.getTitle()));
            others.forEach(row -> {
                if (titles.containsKey(row.getEvent())) {
                    busy.put(row.getUser(), titles.get(row.getEvent()));
                }
            });
        }
        return users.stream().limit(10).map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_id", user.getId());
            row.put("name", user.getName());
            row.put("email", user.getEmail());
            row.put("departmentCode", user.getDepartmentCode());
            row.put("batchCode", user.getBatchCode());
            row.put("avatar", user.getAvatar());
            row.put("available", !taken.contains(user.getId()));
            row.put("busyWith", busy.get(user.getId()));
            return row;
        }).toList();
    }
}
