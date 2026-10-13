package com.campusconnect.feature.election;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubRole;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.election.ClubElection;
import com.campusconnect.domain.election.ElectionBallot;
import com.campusconnect.domain.election.ElectionCandidate;
import com.campusconnect.domain.election.ElectionStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.feature.chat.BodyCheck;
import com.campusconnect.feature.chat.ClubChats;
import com.campusconnect.feature.chat.JsValues;
import com.campusconnect.feature.election.ElectionViews.ElectionList;
import com.campusconnect.feature.election.ElectionViews.ElectionView;
import com.campusconnect.feature.election.ElectionViews.Standings;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
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
 * Club elections, like a student body vote (services/ElectionService.js):
 * <ul>
 *   <li>Someone with "Run elections" (the president always) picks a role and 2–10 candidates from the members, and
 *       when voting opens and closes. Until it opens, everything can still be edited.</li>
 *   <li>Every member who was in the club when voting opened gets one secret vote; votes are final. Only <em>that</em>
 *       a member voted is stored (a ballot); the choice is just added to the candidate's count, atomically, and the
 *       unique ballot index makes a second vote impossible even when two arrive at once.</li>
 *   <li>When voting closes the counts are shown to the club. The result is advisory: the president still appoints
 *       the role. A tie can go to a runoff between the tied candidates.</li>
 * </ul>
 * Elections are internal to the club: its members and its faculty mentor (who follows along read-only).
 */
@Slf4j
@Service
public class ElectionService {

    static final int MIN_CANDIDATES = 2;
    static final int MAX_CANDIDATES = 10;
    static final Duration MIN_VOTING = Duration.ofMinutes(10);
    static final Duration MAX_VOTING = Duration.ofDays(14);
    static final List<ElectionStatus> LIVE = List.of(ElectionStatus.SCHEDULED, ElectionStatus.OPEN);
    private static final String RUN_MESSAGE = "Only members who can run elections can do this";

    /** What an election is created from (the create body, or a runoff built from a tied election). */
    record ElectionInput(Object role, Object title, Object description, Object candidates, Object opensAt, Object closesAt) {
    }

    record Window(Instant opensAt, Instant closesAt) {
    }

    private final MongoOperations mongo;
    private final ClubAccess clubAccess;
    private final ElectionLifecycle lifecycle;
    private final ElectionViews views;
    private final ClubChats clubChats;
    private final NotificationService notifications;
    private final AuditService audit;

    public ElectionService(MongoOperations mongo, ClubAccess clubAccess, ElectionLifecycle lifecycle, ElectionViews views, ClubChats clubChats,
            NotificationService notifications, AuditService audit) {
        this.mongo = mongo;
        this.clubAccess = clubAccess;
        this.lifecycle = lifecycle;
        this.views = views;
        this.clubChats = clubChats;
        this.notifications = notifications;
        this.audit = audit;
    }

    private static AppException invalid(String message) {
        return AppException.badRequest(message);
    }

    private static AppException conflict(String message) {
        return AppException.conflict(message);
    }

    private static AppException notFound() {
        return AppException.notFound("Election not found");
    }

    private ClubElection loadElection(String id) {
        ClubElection election = ObjectId.isValid(String.valueOf(id)) ? mongo.findById(id, ClubElection.class) : null;
        if (election == null) {
            throw notFound();
        }
        return election;
    }

    /** Elections are internal to the club: its members and its faculty mentor. */
    private ClubContext viewContext(AuthUser actor, String clubId) {
        ClubContext context = clubAccess.context(actor, clubId);
        if (!context.isMember() && !context.isMentor()) {
            throw notFound();
        }
        return context;
    }

    private ClubContext manageContext(AuthUser actor, String clubId) {
        ClubContext context = clubAccess.assertPermission(actor, clubId, ClubPermission.RUN_ELECTIONS, RUN_MESSAGE);
        if (context.club().getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("Only active clubs can hold elections", ErrorCode.CLUB_NOT_ACTIVE);
        }
        return context;
    }

    private ClubElection settle(ClubElection election) {
        return lifecycle.settle(election, JsValues.now());
    }

    // ---------------------------------------------------------------- validation

    /** {@code String(value?._id || value || "")} — a candidate is a user id or a {@code { user }} object. */
    private static String idOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            return JsValues.truthy(map.get("_id")) ? JsValues.string(map.get("_id")) : "[object Object]";
        }
        return JsValues.text(value);
    }

    private List<ElectionCandidate> parseCandidates(String clubId, Object list) {
        if (!(list instanceof List<?> entries)) {
            throw invalid("Choose the candidates");
        }
        Set<String> seen = new HashSet<>();
        List<ElectionCandidate> candidates = new ArrayList<>();
        for (Object entry : entries) {
            Object user = entry instanceof Map<?, ?> map && map.get("user") != null ? map.get("user") : entry;
            String userId = idOf(user);
            if (!ObjectId.isValid(userId) || !seen.add(userId)) {
                continue;
            }
            ElectionCandidate candidate = new ElectionCandidate();
            candidate.setUser(userId);
            candidate.setStatement(entry instanceof Map<?, ?> map ? JsValues.clean(map.get("statement"), 300) : "");
            candidates.add(candidate);
        }
        if (candidates.size() < MIN_CANDIDATES) {
            throw invalid("Choose at least " + MIN_CANDIDATES + " candidates");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw invalid("An election can have at most " + MAX_CANDIDATES + " candidates");
        }
        Set<String> members = new HashSet<>(clubAccess.approvedMemberIds(clubId));
        if (candidates.stream().anyMatch(candidate -> !members.contains(candidate.getUser()))) {
            throw invalid("Every candidate must be a member of the club");
        }
        return candidates;
    }

    /** At least 10 minutes and at most 14 days of voting, not opening in the past; opening "now" if no time given. */
    static Window parseWindow(Object opensAt, Object closesAt, Instant now) {
        Instant opens = JsValues.truthy(opensAt) ? JsDates.parse(opensAt) : now;
        Instant closes = JsDates.parse(closesAt);
        if (opens == null) {
            throw invalid("Choose when voting opens");
        }
        if (closes == null) {
            throw invalid("Choose when voting closes");
        }
        if (opens.isBefore(now.minus(Duration.ofMinutes(1)))) {
            throw invalid("Voting can't open in the past");
        }
        Instant start = opens.isAfter(now) ? opens : now;
        if (closes.isBefore(start.plus(MIN_VOTING))) {
            throw invalid("Keep voting open for at least " + MIN_VOTING.toMinutes() + " minutes");
        }
        if (closes.isAfter(start.plus(MAX_VOTING))) {
            throw invalid("Voting can stay open for at most " + MAX_VOTING.toDays() + " days");
        }
        return new Window(opens.isBefore(now) ? now : opens, closes);
    }

    private static ClubRole assertRole(Club club, Object key) {
        ClubRole role = key instanceof String text ? ClubRoles.findRole(club, text) : null;
        if (role == null || ClubRoles.MEMBER.equals(role.getKey())) {
            throw invalid("Choose the role this election is for");
        }
        return role;
    }

    private void assertNoOtherLive(String clubId, String role, String exceptId) {
        Criteria criteria = Criteria.where("club").is(new ObjectId(clubId)).and("role").is(role).and("status").in(LIVE);
        if (exceptId != null) {
            criteria = criteria.and("_id").ne(new ObjectId(exceptId));
        }
        if (mongo.exists(Query.query(criteria), ClubElection.class)) {
            throw conflict("There's already an election under way for this role");
        }
    }

    private void record(AuditAction action, AuthUser actor, ClubElection election, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", new ObjectId(election.getClub()));
        metadata.putAll(extra);
        audit.record(action, actor.id(), "ClubElection", election.getId(), metadata);
    }

    // ---------------------------------------------------------------- actions

    public ElectionList listElections(AuthUser actor, String clubId) {
        ClubContext context = viewContext(actor, clubId);
        Query query = Query.query(Criteria.where("club").is(new ObjectId(context.club().getId()))).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(50);
        List<ClubElection> settled = new ArrayList<>();
        for (ClubElection election : mongo.find(query, ClubElection.class)) {
            settled.add(settle(election));
        }
        return new ElectionList(ElectionViews.canManage(context), views.viewOf(settled, actor, context));
    }

    public ElectionView getElection(AuthUser actor, String electionId) {
        ClubElection election = settle(loadElection(electionId));
        ClubContext context = viewContext(actor, election.getClub());
        return views.viewOne(election, actor, context);
    }

    public ElectionView createElection(AuthUser actor, ElectionRequests.CreateBody body) {
        return create(actor, body.club(), new ElectionInput(body.role(), body.title(), body.description(), body.candidates(), body.opensAt(), body.closesAt()), null);
    }

    private ElectionView create(AuthUser actor, String clubId, ElectionInput input, String runoffOf) {
        ClubContext context = manageContext(actor, clubId);
        Club club = context.club();
        ClubRole role = assertRole(club, input.role());
        assertNoOtherLive(club.getId(), role.getKey(), null);
        List<ElectionCandidate> candidates = parseCandidates(club.getId(), input.candidates());
        Window window = parseWindow(input.opensAt(), input.closesAt(), JsValues.now());
        String name = ClubRoles.roleName(club, role.getKey());

        ClubElection election = new ClubElection();
        election.setClub(club.getId());
        election.setRole(role.getKey());
        election.setRoleName(name);
        String title = JsValues.clean(input.title(), 120);
        election.setTitle(title.isEmpty() ? name + " election" : title);
        election.setDescription(JsValues.clean(input.description(), 1000));
        election.setCandidates(candidates);
        election.setOpensAt(window.opensAt());
        election.setClosesAt(window.closesAt());
        election.setRunoffOf(runoffOf);
        election.setCreatedBy(actor.id());
        mongo.insert(election);
        election.setCreatedAt(JsValues.millis(election.getCreatedAt()));
        election.setUpdatedAt(JsValues.millis(election.getUpdatedAt()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("role", role.getKey());
        metadata.put("runoffOf", runoffOf == null ? null : new ObjectId(runoffOf));
        record(AuditAction.ELECTION_CREATED, actor, election, metadata);

        // The card goes into the club group straight away ("Voting opens at …" until it does).
        try {
            clubChats.postElectionCard(club.getId(), election, actor);
        } catch (RuntimeException e) {
            log.warn("Election card failed: {}", e.getMessage());
        }
        return views.viewOne(settle(election), actor, context);
    }

    public ElectionView updateElection(AuthUser actor, String electionId, Map<String, Object> body) {
        BodyCheck.of(body)
                .optionalString("role", Integer.MAX_VALUE, "Invalid value")
                .optionalString("title", 120, "Invalid value")
                .optionalString("description", 1000, "Invalid value")
                .optionalArray("candidates", MAX_CANDIDATES, "Choose up to 10 candidates")
                .optional("opensAt", true, value -> value instanceof String && JsDates.parse(value) != null, "Choose when voting opens")
                .optional("closesAt", false, value -> value instanceof String && JsDates.parse(value) != null, "Choose when voting closes")
                .validate();
        ClubElection election = settle(loadElection(electionId));
        ClubContext context = manageContext(actor, election.getClub());
        if (election.getStatus() != ElectionStatus.SCHEDULED) {
            throw conflict("An election can be changed only until voting opens");
        }
        Update update = new Update();
        String roleName = election.getRoleName();
        if (body.containsKey("role") && !Objects.equals(body.get("role"), election.getRole())) {
            ClubRole role = assertRole(context.club(), body.get("role"));
            assertNoOtherLive(election.getClub(), role.getKey(), election.getId());
            roleName = ClubRoles.roleName(context.club(), role.getKey());
            update.set("role", role.getKey()).set("roleName", roleName);
        }
        if (body.containsKey("title")) {
            String title = JsValues.clean(body.get("title"), 120);
            update.set("title", title.isEmpty() ? roleName + " election" : title);
        }
        if (body.containsKey("description")) {
            update.set("description", JsValues.clean(body.get("description"), 1000));
        }
        if (body.containsKey("candidates")) {
            update.set("candidates", parseCandidates(election.getClub(), body.get("candidates")));
        }
        if (body.containsKey("opensAt") || body.containsKey("closesAt")) {
            // opensAt: null means "open it now".
            Object opensAt = body.containsKey("opensAt") && body.get("opensAt") == null ? null : (body.get("opensAt") != null ? body.get("opensAt") : election.getOpensAt());
            Object closesAt = body.get("closesAt") != null ? body.get("closesAt") : election.getClosesAt();
            Window window = parseWindow(opensAt, closesAt, JsValues.now());
            update.set("opensAt", window.opensAt()).set("closesAt", window.closesAt());
        }
        // Only while still scheduled: the sweeper may have opened it a moment ago.
        ClubElection updated = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(election.getId())).and("status").is(ElectionStatus.SCHEDULED)),
                update.set("updatedAt", JsValues.now()),
                FindAndModifyOptions.options().returnNew(true),
                ClubElection.class);
        if (updated == null) {
            throw conflict("An election can be changed only until voting opens");
        }
        record(AuditAction.ELECTION_UPDATED, actor, updated, Map.of());
        lifecycle.signal(updated);
        return views.viewOne(settle(updated), actor, context);
    }

    public ElectionView vote(AuthUser actor, String electionId, String candidateId) {
        ClubElection election = settle(loadElection(electionId));
        ClubContext context = viewContext(actor, election.getClub());
        if (election.getStatus() != ElectionStatus.OPEN) {
            throw conflict(election.getStatus() == ElectionStatus.SCHEDULED ? "Voting hasn't opened yet" : "Voting has closed");
        }
        if (!ElectionViews.isVoter(context.membership(), election)) {
            throw AppException.forbidden(context.isMember() ? "Only members who were in the club when voting opened can vote" : "Only club members can vote");
        }
        ElectionCandidate candidate = election.getCandidates().stream().filter(entry -> entry.getUser().equals(candidateId)).findFirst().orElse(null);
        if (candidate == null) {
            throw invalid("Choose one of the candidates");
        }
        boolean stillMember = mongo.exists(Query.query(Criteria.where("club").is(new ObjectId(election.getClub()))
                .and("user").is(new ObjectId(candidate.getUser())).and("status").is(MembershipStatus.APPROVED)), ClubMembership.class);
        if (!stillMember) {
            throw invalid("This candidate has left the club");
        }

        // One ballot per member (the unique index settles races), then the count goes to the candidate.
        ElectionBallot ballot = new ElectionBallot();
        ballot.setElection(election.getId());
        ballot.setVoter(actor.id());
        try {
            mongo.insert(ballot);
        } catch (DuplicateKeyException alreadyVoted) {
            throw conflict("You've already voted in this election");
        }
        long counted = mongo.updateFirst(
                Query.query(Criteria.where("_id").is(new ObjectId(election.getId())).and("status").is(ElectionStatus.OPEN)
                        .and("candidates.user").is(new ObjectId(candidate.getUser()))),
                new Update().inc("candidates.$.votes", 1).inc("votesCast", 1).set("updatedAt", JsValues.now()),
                ClubElection.class).getModifiedCount();
        if (counted == 0) {
            mongo.remove(Query.query(Criteria.where("_id").is(new ObjectId(ballot.getId()))), ElectionBallot.class);
            throw conflict("Voting has closed");
        }
        lifecycle.signal(election);
        ClubElection fresh = mongo.findById(election.getId(), ClubElection.class);
        return views.viewOne(fresh != null ? fresh : election, actor, context);
    }

    public ElectionView closeElection(AuthUser actor, String electionId) {
        ClubElection election = settle(loadElection(electionId));
        ClubContext context = manageContext(actor, election.getClub());
        if (election.getStatus() != ElectionStatus.OPEN) {
            throw conflict(election.getStatus() == ElectionStatus.SCHEDULED ? "Voting hasn't opened yet — cancel it instead" : "This election has already ended");
        }
        return views.viewOne(lifecycle.closeNow(election, actor, true), actor, context);
    }

    public ElectionView cancelElection(AuthUser actor, String electionId, String reason) {
        ClubElection election = settle(loadElection(electionId));
        ClubContext context = manageContext(actor, election.getClub());
        if (!LIVE.contains(election.getStatus())) {
            throw conflict("This election has already ended");
        }
        boolean wasOpen = election.getStatus() == ElectionStatus.OPEN;
        String note = JsValues.clean(reason, 300);
        Instant now = JsValues.now();
        ClubElection claimed = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(election.getId())).and("status").in(LIVE)),
                new Update().set("status", ElectionStatus.CANCELLED).set("cancelledAt", now).set("cancelReason", note).set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true),
                ClubElection.class);
        if (claimed == null) {
            throw conflict("This election has already ended");
        }
        record(AuditAction.ELECTION_CANCELLED, actor, claimed, Map.of("reason", note));

        // Voters had been told to vote, so they hear it was called off; before opening, the group notice is enough.
        if (wasOpen) {
            notifications.notify(lifecycle.voterIds(claimed), Notice.builder()
                    .type(NotificationType.ELECTION)
                    .title("Cancelled: " + claimed.getTitle())
                    .message(note.isEmpty() ? "The election was cancelled. No result will be announced." : note)
                    .link(ElectionLifecycle.link(claimed))
                    .exclude(List.of(actor.id()))
                    .build());
        }
        try {
            clubChats.postClubNotice(claimed.getClub(), ElectionLifecycle.BALLOT + " “" + claimed.getTitle() + "” was cancelled" + (note.isEmpty() ? "." : ": " + note),
                    "ELECTION_CANCELLED");
        } catch (RuntimeException e) {
            log.debug("Election cancel notice failed: {}", e.getMessage());
        }
        lifecycle.signal(claimed);
        return views.viewOne(claimed, actor, context);
    }

    /** A new vote between the candidates who tied. */
    public ElectionView startRunoff(AuthUser actor, String electionId, ElectionRequests.DetailsBody body) {
        ClubElection election = settle(loadElection(electionId));
        manageContext(actor, election.getClub());
        if (election.getStatus() != ElectionStatus.CLOSED) {
            throw conflict("A runoff can start once voting has closed");
        }
        Standings standings = ElectionViews.standings(election);
        if (!standings.tie()) {
            throw conflict("A runoff is only needed when candidates tie");
        }
        ElectionRequests.DetailsBody details = body == null ? new ElectionRequests.DetailsBody(null, null, null, null, null) : body;
        List<Object> candidates = new ArrayList<>();
        for (ElectionCandidate leader : standings.leaders()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user", leader.getUser());
            entry.put("statement", leader.getStatement() == null ? "" : leader.getStatement());
            candidates.add(entry);
        }
        ElectionInput input = new ElectionInput(
                election.getRole(),
                JsValues.truthy(details.title()) ? details.title() : election.getTitle() + " — runoff",
                details.description() != null ? details.description() : election.getDescription(),
                candidates,
                details.opensAt(),
                details.closesAt());
        return create(actor, election.getClub(), input, election.getId());
    }
}
