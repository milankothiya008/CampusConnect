package com.campusconnect.feature.team;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.util.Updates;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
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
import com.campusconnect.feature.event.Values;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * Teams as registration sees them (the data side of services/TeamService.js): the leader registers the team, which
 * takes the team's place (or its spot on the waitlist), and invites teammates; accepted members get their own
 * registration linked to the team, with the leader's status. A team reaching the minimum size is "complete".
 */
@Service
public class Teams {

    public static final List<String> ACTIVE = List.of(RegistrationStatus.REGISTERED.name(), RegistrationStatus.WAITLISTED.name());
    static final List<TeamMemberStatus> SHOWN = List.of(TeamMemberStatus.LEADER, TeamMemberStatus.ACCEPTED, TeamMemberStatus.INVITED);

    private final MongoOperations mongo;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Refs refs;

    public Teams(MongoOperations mongo, NotificationService notifications, AuditService audit, Refs refs) {
        this.mongo = mongo;
        this.notifications = notifications;
        this.audit = audit;
        this.refs = refs;
    }

    static String eventLink(Event event) {
        return "/events/" + event.getId();
    }

    static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(new ObjectId(id)));
    }

    /** Why this student can't take part, worded for teams ("open to CE students only"), or null. */
    public static String eligibilityProblem(String departmentCode, String batchCode, Event event) {
        List<String> departments = event.getEligibility() == null ? List.of() : event.getEligibility().getDepartments();
        List<String> batches = event.getEligibility() == null ? List.of() : event.getEligibility().getBatches();
        if (!departments.isEmpty() && !departments.contains(departmentCode)) {
            return "open to " + String.join(", ", departments) + " students only";
        }
        if (!batches.isEmpty() && !batches.contains(batchCode)) {
            return "open to batch " + batches.stream().map(batch -> "20" + batch).collect(Collectors.joining(", ")) + " only";
        }
        return null;
    }

    public static String normalizeTeamName(Object name) {
        String value = Values.text(Values.truthy(name) ? name : "").strip().replaceAll("\\s+", " ");
        if (value.length() < 2 || value.length() > 60) {
            throw AppException.badRequest("Team name must be 2-60 characters", ErrorCode.VALIDATION_ERROR);
        }
        return value;
    }

    static List<TeamMember> activeMembers(Team team) {
        return team == null ? List.of() : team.getMembers().stream()
                .filter(member -> member.getStatus() == TeamMemberStatus.LEADER || member.getStatus() == TeamMemberStatus.ACCEPTED).toList();
    }

    static List<TeamMember> pendingInvites(Team team) {
        return team == null ? List.of() : team.getMembers().stream().filter(member -> member.getStatus() == TeamMemberStatus.INVITED).toList();
    }

    /** An invited student: who they are, as far as invites need to know. */
    public record Invitee(String id, String name) {
    }

    /**
     * Checks the students a leader wants to invite: verified students eligible for the event, not already registered
     * for it and not already on this team — and there must be room on the team, counting pending invites.
     */
    public List<Invitee> checkInvitees(Event event, String leaderId, Collection<String> userIds, Team team) {
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(userIds == null ? List.of() : userIds));
        if (ids.contains(leaderId)) {
            throw AppException.badRequest("You're already on your team — invite your teammates", ErrorCode.VALIDATION_ERROR);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<String> onTeam = team == null ? Set.of() : team.getMembers().stream()
                .filter(member -> SHOWN.contains(member.getStatus())).map(TeamMember::getUser).collect(Collectors.toSet());
        if (ids.stream().anyMatch(onTeam::contains)) {
            throw AppException.conflict("Some of these students are already on your team or invited", ErrorCode.CONFLICT);
        }
        int taken = activeMembers(team).isEmpty() ? 1 : activeMembers(team).size();
        int room = event.getMaxTeamSize() - taken - pendingInvites(team).size();
        if (ids.size() > room) {
            throw AppException.badRequest(room > 0
                    ? "Your team has room for " + room + " more invite" + (room == 1 ? "" : "s") + " (teams have up to " + event.getMaxTeamSize() + " members)"
                    : "Your team is at the maximum of " + event.getMaxTeamSize() + " members, counting pending invites", ErrorCode.VALIDATION_ERROR);
        }
        Query query = Query.query(Criteria.where("_id").in(ids.stream().map(ObjectId::new).toList()));
        query.fields().include("name", "departmentCode", "batchCode", "accountType", "globalRole", "isEmailVerified", "isActive");
        List<User> users = mongo.find(query, User.class);
        if (users.size() != ids.size()) {
            throw AppException.badRequest("Some of the invited students could not be found", ErrorCode.VALIDATION_ERROR);
        }
        for (User user : users) {
            if (user.getGlobalRole() != GlobalRole.STUDENT || user.getAccountType() != AccountType.STUDENT || !user.isEmailVerified() || !user.isActive()) {
                throw AppException.badRequest(user.getName() + " can't join teams (only verified student accounts can)", ErrorCode.VALIDATION_ERROR);
            }
            String problem = eligibilityProblem(user.getDepartmentCode(), user.getBatchCode(), event);
            if (problem != null) {
                throw AppException.forbidden(user.getName() + " can't join: this event is " + problem, ErrorCode.NOT_ELIGIBLE);
            }
        }
        Query registered = Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").in(ids.stream().map(ObjectId::new).toList())
                .and("status").in(ACTIVE));
        registered.fields().include("user");
        List<EventRegistration> alreadyRegistered = mongo.find(registered, EventRegistration.class);
        if (!alreadyRegistered.isEmpty()) {
            Map<String, Map<String, Object>> names = refs.users(alreadyRegistered.stream().map(EventRegistration::getUser).toList(), "name");
            String who = alreadyRegistered.stream().map(row -> names.containsKey(row.getUser()) ? String.valueOf(names.get(row.getUser()).get("name")) : "")
                    .collect(Collectors.joining(", "));
            throw AppException.conflict(who + " " + (alreadyRegistered.size() == 1 ? "is" : "are") + " already registered for this event", ErrorCode.DUPLICATE_REGISTRATION);
        }
        return users.stream().map(user -> new Invitee(user.getId(), user.getName())).toList();
    }

    public void sendInvites(Team team, Event event, AuthUser leader, List<Invitee> invitees) {
        if (invitees.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.getId());
        metadata.put("invited", invitees.stream().map(Invitee::id).toList());
        audit.record(AuditAction.TEAM_INVITE_SENT, leader.id(), "Team", team.getId(), metadata);
        notifications.notify(invitees.stream().map(Invitee::id).toList(), Notice.builder()
                .type(NotificationType.TEAM_INVITE)
                .title(leader.name() + " invited you to join \"" + team.getName() + "\" for " + event.getTitle())
                .message("Accept the invite to be registered with the team for " + CampusTime.formatDateKey(CampusTime.toDateKey(event.getEventDate())) + " at "
                        + event.getStartTime() + ". Invites can be answered until registration closes.")
                .link(eventLink(event))
                .email(true)
                .emailCategory(EmailCategory.EVENT_ACTIVITY)
                .build());
    }

    public record Created(Team team, List<Invitee> invitees) {
    }

    /** Called when the leader registers: creates the team before the leader's place is taken. */
    public Created createTeam(AuthUser leader, Event event, Object teamName, List<String> invitees, List<FormAnswer> answers) {
        String name = normalizeTeamName(teamName);
        List<Invitee> users = checkInvitees(event, leader.id(), invitees, null);
        Instant now = Instant.now();
        Team team = new Team();
        team.setEvent(event.getId());
        team.setName(name);
        team.setNameKey(name.toLowerCase(Locale.ROOT));
        team.setLeader(leader.id());
        List<TeamMember> members = new ArrayList<>();
        members.add(member(leader.id(), TeamMemberStatus.LEADER, now, now));
        users.forEach(user -> members.add(member(user.id(), TeamMemberStatus.INVITED, now, null)));
        team.setMembers(members);
        team.setSize(1);
        team.setAnswers(new ArrayList<>(answers == null ? List.of() : answers));
        try {
            team = mongo.insert(team);
        } catch (DuplicateKeyException e) {
            throw AppException.conflict("A team called \"" + name + "\" is already registered for this event — pick another name", ErrorCode.CONFLICT);
        }
        return new Created(team, users);
    }

    static TeamMember member(String userId, TeamMemberStatus status, Instant invitedAt, Instant respondedAt) {
        TeamMember member = new TeamMember();
        member.setUser(userId);
        member.setStatus(status);
        member.setInvitedAt(invitedAt);
        member.setRespondedAt(respondedAt);
        return member;
    }

    public void afterTeamCreated(Team team, Event event, AuthUser leader, List<Invitee> invitees) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.getId());
        metadata.put("name", team.getName());
        audit.record(AuditAction.TEAM_CREATED, leader.id(), "Team", team.getId(), metadata);
        sendInvites(team, event, leader, invitees);
    }

    /** Removes a team whose leader could not take a place after all (nothing else refers to it yet). */
    public void discardTeam(Team team) {
        mongo.remove(byId(team.getId()), Team.class);
    }

    /** A member leaving on their own (called when they cancel their registration). */
    public Map<String, Object> leaveTeam(AuthUser actor, Event event, EventRegistration registration) {
        EventRegistration cancelled = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(registration.getId())).and("status").in(ACTIVE)),
                Updates.set("status", RegistrationStatus.CANCELLED.name()),
                FindAndModifyOptions.options().returnNew(true), EventRegistration.class);
        if (cancelled == null) {
            throw AppException.notFound("You are not registered for this event");
        }
        Team team = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(registration.getTeam()))
                        .and("members").elemMatch(Criteria.where("user").is(new ObjectId(actor.id())).and("status").is(TeamMemberStatus.ACCEPTED.name()))),
                Updates.touch(new Update().set("members.$.status", TeamMemberStatus.LEFT.name()).set("members.$.respondedAt", Instant.now()).inc("size", -1)),
                FindAndModifyOptions.options().returnNew(true), Team.class);
        audit.record(AuditAction.TEAM_MEMBER_LEFT, actor.id(), "Team", registration.getTeam(), Map.of("eventId", event.getId()));
        if (team != null) {
            int missing = event.getMinTeamSize() - team.getSize();
            notifications.notify(team.getLeader(), Notice.builder()
                    .type(NotificationType.TEAM_UPDATE)
                    .title(actor.name() + " left \"" + team.getName() + "\"")
                    .message(team.getSize() < event.getMinTeamSize()
                            ? "Your team needs " + missing + " more member" + (missing == 1 ? "" : "s") + " to be complete."
                            : "Your team is still complete.")
                    .link(eventLink(event))
                    .email(true)
                    .emailCategory(EmailCategory.EVENT_ACTIVITY)
                    .build());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("leftTeam", true);
        return result;
    }

    /** Ends a team whose leader cancelled (or was removed): members' registrations and open invites are cancelled. */
    public void disbandTeam(String teamId, Event event, AuthUser actor, boolean byOrganiser, String reason) {
        if (teamId == null) {
            return;
        }
        Team team = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(teamId)).and("status").is(TeamStatus.ACTIVE.name())),
                Updates.touch(new Update().set("status", TeamStatus.DISBANDED.name()).set("disbandedAt", Instant.now())),
                FindAndModifyOptions.options().returnNew(true), Team.class);
        if (team == null) {
            return;
        }
        Query membersQuery = Query.query(Criteria.where("team").is(new ObjectId(team.getId())).and("teamRole").is(TeamRole.MEMBER.name()).and("status").in(ACTIVE));
        membersQuery.fields().include("user");
        List<EventRegistration> members = mongo.find(membersQuery, EventRegistration.class);
        if (!members.isEmpty()) {
            mongo.updateMulti(Query.query(Criteria.where("_id").in(members.stream().map(row -> new ObjectId(row.getId())).toList())),
                    Updates.set("status", RegistrationStatus.CANCELLED.name()), EventRegistration.class);
        }
        mongo.updateFirst(byId(team.getId()),
                Updates.touch(new Update().set("members.$[invited].status", TeamMemberStatus.CANCELLED.name())
                        .filterArray(Criteria.where("invited.status").is(TeamMemberStatus.INVITED.name()))), Team.class);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.getId());
        metadata.put("byOrganiser", byOrganiser);
        audit.record(AuditAction.TEAM_DISBANDED, actor != null ? actor.id() : team.getLeader(), "Team", team.getId(), null, null, reason, metadata);
        notifications.notify(members.stream().map(EventRegistration::getUser).toList(), Notice.builder()
                .type(NotificationType.TEAM_UPDATE)
                .title("\"" + team.getName() + "\" is no longer registered for " + event.getTitle())
                .message(byOrganiser
                        ? "The organisers removed the team" + (Values.truthy(reason) ? ": " + reason : ".") + " Your ticket is no longer valid."
                        : "The team leader cancelled the team's registration, so your ticket is no longer valid. You can register again on your own or with another team while registration is open.")
                .link(eventLink(event))
                .email(true)
                .build());
    }

    // ---------------------------------------------------------------- views

    private static Map<String, Object> userView(String userId, Map<String, Map<String, Object>> users) {
        Map<String, Object> user = users.get(userId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", userId);
        if (user == null) {
            view.put("name", "Former student");
            return view;
        }
        view.put("name", user.get("name"));
        view.put("email", user.get("email"));
        view.put("departmentCode", user.get("departmentCode"));
        view.put("batchCode", user.get("batchCode"));
        return view;
    }

    private static Map<String, Object> memberView(TeamMember member, Map<String, Map<String, Object>> users) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("user", userView(member.getUser(), users));
        view.put("status", member.getStatus());
        view.put("invitedAt", member.getInvitedAt());
        view.put("respondedAt", member.getRespondedAt());
        return view;
    }

    /** The team as the leader, its members and the organisers see it; null when the team is gone. */
    public Map<String, Object> getTeamView(String teamId, Event event) {
        Team team = teamId == null ? null : mongo.findById(teamId, Team.class);
        if (team == null) {
            return null;
        }
        return getTeamViews(List.of(team), event).get(0);
    }

    /** Views of several teams of one event with one user query and one registration query in all. */
    public List<Map<String, Object>> getTeamViews(List<Team> teams, Event event) {
        if (teams.isEmpty()) {
            return List.of();
        }
        Event teamEvent = event != null ? event : mongo.findById(teams.get(0).getEvent(), Event.class);
        List<String> shownUsers = teams.stream().flatMap(team -> team.getMembers().stream())
                .filter(member -> SHOWN.contains(member.getStatus())).map(TeamMember::getUser).distinct().toList();
        Map<String, Map<String, Object>> users = refs.users(shownUsers, "name", "email", "departmentCode", "batchCode", "avatar");
        Query leaders = Query.query(Criteria.where("team").in(teams.stream().map(team -> new ObjectId(team.getId())).toList()).and("teamRole").is(TeamRole.LEADER.name()));
        leaders.fields().include("team", "status");
        Map<String, RegistrationStatus> leaderStatus = new HashMap<>();
        mongo.find(leaders, EventRegistration.class).forEach(row -> leaderStatus.putIfAbsent(row.getTeam(), row.getStatus()));

        List<Map<String, Object>> views = new ArrayList<>();
        for (Team team : teams) {
            List<TeamMember> shown = team.getMembers().stream().filter(member -> SHOWN.contains(member.getStatus())).toList();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("_id", team.getId());
            view.put("name", team.getName());
            view.put("status", team.getStatus());
            view.put("registrationStatus", leaderStatus.get(team.getId()));
            view.put("leader", userView(team.getLeader(), users));
            view.put("members", shown.stream().filter(member -> member.getStatus() != TeamMemberStatus.INVITED).map(member -> memberView(member, users)).toList());
            view.put("invites", shown.stream().filter(member -> member.getStatus() == TeamMemberStatus.INVITED).map(member -> memberView(member, users)).toList());
            view.put("size", team.getSize());
            view.put("minSize", teamEvent == null ? null : teamEvent.getMinTeamSize());
            view.put("maxSize", teamEvent == null ? null : teamEvent.getMaxTeamSize());
            view.put("complete", teamEvent != null && team.getSize() >= teamEvent.getMinTeamSize());
            view.put("createdAt", team.getCreatedAt());
            views.add(view);
        }
        return views;
    }

    /** Pending invites for one student, optionally for one event, with who sent them (open events only). */
    public List<Map<String, Object>> invitesFor(AuthUser user, String eventId) {
        Criteria criteria = Criteria.where("status").is(TeamStatus.ACTIVE.name())
                .and("members").elemMatch(Criteria.where("user").is(new ObjectId(user.id())).and("status").is(TeamMemberStatus.INVITED.name()));
        if (eventId != null) {
            criteria = criteria.and("event").is(new ObjectId(eventId));
        }
        List<Team> teams = mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt")), Team.class);
        if (teams.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> leaders = refs.users(teams.stream().map(Team::getLeader).toList(), "name");
        Map<String, Map<String, Object>> events = refs.events(teams.stream().map(Team::getEvent).toList(),
                "title", "startAt", "endAt", "startTime", "eventDate", "status", "registrationEnd", "minTeamSize", "maxTeamSize", "poster", "category", "club");
        Map<String, Map<String, Object>> clubs = refs.clubs(events.values().stream().map(row -> (String) row.get("club")).toList(), "name", "logo");
        Instant now = Instant.now();
        List<Map<String, Object>> invites = new ArrayList<>();
        for (Team team : teams) {
            Map<String, Object> event = events.get(team.getEvent());
            if (event == null || !EventStatus.PUBLISHED.name().equals(event.get("status"))
                    || !(event.get("registrationEnd") instanceof Instant closes && closes.isAfter(now))
                    || !(event.get("startAt") instanceof Instant starts && starts.isAfter(now))) {
                continue;
            }
            Map<String, Object> eventView = new LinkedHashMap<>(event);
            eventView.put("club", clubs.get((String) event.get("club")));
            Map<String, Object> teamView = new LinkedHashMap<>();
            teamView.put("_id", team.getId());
            teamView.put("name", team.getName());
            teamView.put("size", team.getSize());
            teamView.put("leader", leaders.get(team.getLeader()));
            Map<String, Object> invite = new LinkedHashMap<>();
            invite.put("team", teamView);
            invite.put("event", eventView);
            invite.put("invitedAt", team.getMembers().stream().filter(member -> user.id().equals(member.getUser())).map(TeamMember::getInvitedAt).findFirst().orElse(null));
            invites.add(invite);
        }
        return invites;
    }
}
