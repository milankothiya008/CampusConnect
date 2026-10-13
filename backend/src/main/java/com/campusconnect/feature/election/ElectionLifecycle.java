package com.campusconnect.feature.election;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.election.ClubElection;
import com.campusconnect.domain.election.ElectionStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ClubChats;
import com.campusconnect.feature.chat.JsValues;
import com.campusconnect.feature.election.ElectionViews.Standings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Opening and closing elections when their time comes (ElectionService openIfDue / closeNow / settle /
 * sweepElections). Every transition is claimed with one conditional update on the status, so a request and the
 * sweeper (or two replicas) can never open or close the same election twice; announcements follow the claim and
 * never undo it. Voting opens to the members who were in the club at that moment; the result goes to the club and
 * its faculty mentor, as a notification and a notice in the club group chat. Methods other classes call are public:
 * the scheduler lock makes this bean a class-based proxy in production.
 */
@Slf4j
@Service
public class ElectionLifecycle {

    static final String BALLOT = "🗳️";

    private final MongoOperations mongo;
    private final ClubChats clubChats;
    private final NotificationService notifications;
    private final AuditService audit;

    public ElectionLifecycle(MongoOperations mongo, ClubChats clubChats, NotificationService notifications, AuditService audit) {
        this.mongo = mongo;
        this.clubChats = clubChats;
        this.notifications = notifications;
        this.audit = audit;
    }

    static String link(ClubElection election) {
        return "/clubs/" + election.getClub() + "/elections/" + election.getId();
    }

    /** The club's approved memberships with what voting needs (who, and since when). */
    public List<ClubMembership> approvedMembers(String clubId) {
        Query query = Query.query(Criteria.where("club").is(new ObjectId(clubId)).and("status").is(MembershipStatus.APPROVED));
        query.fields().include("user").include("role").include("joinedAt").include("createdAt");
        return mongo.find(query, ClubMembership.class);
    }

    /** Members who may vote: in the club (approved) since before voting opened. */
    public List<String> voterIds(ClubElection election) {
        return approvedMembers(election.getClub()).stream()
                .filter(row -> {
                    Instant joined = ElectionViews.joinedOf(row);
                    return joined != null && !joined.isAfter(election.getOpensAt());
                })
                .map(ClubMembership::getUser)
                .toList();
    }

    public String resultLine(ClubElection election) {
        Standings standings = ElectionViews.standings(election);
        if (standings.leaders().isEmpty()) {
            return "No votes were cast in “" + election.getTitle() + "”.";
        }
        Query query = Query.query(Criteria.where("_id").in(standings.leaders().stream().map(leader -> new ObjectId(leader.getUser())).toList()));
        query.fields().include("name");
        List<String> names = mongo.find(query, User.class).stream().map(User::getName).toList();
        int cast = election.getVotesCast();
        String of = standings.top() + " of " + cast + " vote" + (cast == 1 ? "" : "s");
        return standings.tie()
                ? "“" + election.getTitle() + "” ended in a tie: " + String.join(" and ", names) + " (" + of + " each)."
                : (names.isEmpty() ? "A candidate" : names.get(0)) + " received the most votes in “" + election.getTitle() + "” (" + of + ").";
    }

    private void announceOpen(ClubElection election) {
        List<String> voters = voterIds(election);
        Query clubQuery = Query.query(Criteria.where("_id").is(new ObjectId(election.getClub())));
        clubQuery.fields().include("name");
        Club club = mongo.findOne(clubQuery, Club.class);
        String clubName = club != null && JsValues.truthy(club.getName()) ? club.getName() : "your club";
        notifications.notify(voters, Notice.builder()
                .type(NotificationType.ELECTION)
                .title("Vote now: " + election.getTitle())
                .message("Choose " + clubName + "'s next " + election.getRoleName() + ". Your vote is secret. Voting closes "
                        + CampusTime.formatDate(election.getClosesAt()) + ", " + CampusTime.formatTime(election.getClosesAt()) + ".")
                .link(link(election))
                .build());
        clubChats.postClubNotice(election.getClub(), BALLOT + " Voting is open: " + election.getTitle(), "ELECTION_OPEN");
    }

    private void announceResult(ClubElection election, boolean early) {
        String line = resultLine(election);
        Query clubQuery = Query.query(Criteria.where("_id").is(new ObjectId(election.getClub())));
        clubQuery.fields().include("mentor");
        Club club = mongo.findOne(clubQuery, Club.class);
        List<String> audience = new ArrayList<>(approvedMembers(election.getClub()).stream().map(ClubMembership::getUser).toList());
        if (club != null && club.getMentor() != null) {
            audience.add(club.getMentor());
        }
        notifications.notify(audience, Notice.builder()
                .type(NotificationType.ELECTION)
                .title("Results: " + election.getTitle())
                .message(line + " The president makes the appointment.")
                .link(link(election))
                .build());
        clubChats.postClubNotice(election.getClub(), BALLOT + " " + (early ? "Voting was closed early. " : "Voting has closed. ") + line, "ELECTION_CLOSED");
    }

    public void signal(ClubElection election) {
        try {
            clubChats.clubSignal(election.getClub(), "election:updated", Map.of("electionId", election.getId()));
        } catch (RuntimeException e) {
            log.debug("Election signal failed: {}", e.getMessage());
        }
    }

    private ClubElection reload(ClubElection election) {
        ClubElection fresh = mongo.findById(election.getId(), ClubElection.class);
        return fresh != null ? fresh : election;
    }

    /** Opens voting when its time has come. Safe to call any number of times, from anywhere. */
    public ClubElection openIfDue(ClubElection election, Instant now) {
        if (election.getStatus() != ElectionStatus.SCHEDULED || election.getOpensAt().isAfter(now)) {
            return election;
        }
        int eligibleCount = voterIds(election).size();
        ClubElection claimed = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(election.getId())).and("status").is(ElectionStatus.SCHEDULED)),
                new Update().set("status", ElectionStatus.OPEN).set("eligibleCount", eligibleCount).set("updatedAt", JsValues.now()),
                FindAndModifyOptions.options().returnNew(true),
                ClubElection.class);
        if (claimed == null) {
            return reload(election);
        }
        try {
            announceOpen(claimed);
        } catch (RuntimeException e) {
            log.warn("Election open notice failed: {}", e.getMessage());
        }
        signal(claimed);
        return claimed;
    }

    /** Ends voting now (by the organiser, or the sweeper when the time is up) and announces the result. */
    public ClubElection closeNow(ClubElection election, AuthUser actor, boolean early) {
        Instant now = JsValues.now();
        ClubElection claimed = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(election.getId())).and("status").is(ElectionStatus.OPEN)),
                new Update().set("status", ElectionStatus.CLOSED).set("closedAt", now).set("closedBy", actor == null ? null : new ObjectId(actor.id())).set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true),
                ClubElection.class);
        if (claimed == null) {
            return reload(election);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", new ObjectId(claimed.getClub()));
        metadata.put("early", early);
        audit.record(AuditAction.ELECTION_CLOSED, actor != null ? actor.id() : claimed.getCreatedBy(), "ClubElection", claimed.getId(), metadata);
        try {
            announceResult(claimed, early);
        } catch (RuntimeException e) {
            log.warn("Election result notice failed: {}", e.getMessage());
        }
        signal(claimed);
        return claimed;
    }

    /** Brings an election up to date with the clock (opens or closes it when due). */
    public ClubElection settle(ClubElection election, Instant now) {
        ClubElection current = openIfDue(election, now);
        if (current.getStatus() == ElectionStatus.OPEN && !current.getClosesAt().isAfter(now)) {
            current = closeNow(current, null, false);
        }
        return current;
    }

    /** Opens and closes every election that is due (at most 100 per run); returns how many were due. */
    public int sweep(Instant now) {
        Query due = Query.query(new Criteria().orOperator(
                        Criteria.where("status").is(ElectionStatus.SCHEDULED).and("opensAt").lte(now),
                        Criteria.where("status").is(ElectionStatus.OPEN).and("closesAt").lte(now)))
                .limit(100);
        List<ClubElection> elections = mongo.find(due, ClubElection.class);
        for (ClubElection election : elections) {
            settle(election, now);
        }
        return elections.size();
    }

    public int sweep() {
        return sweep(JsValues.now());
    }

    /** Every minute, like the original event sweeper; one replica at a time. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "electionSweep", lockAtMostFor = "PT5M")
    public void scheduledSweep() {
        try {
            int due = sweep();
            if (due > 0) {
                log.info("Election sweep: {} due", due);
            }
        } catch (RuntimeException e) {
            log.error("Election sweep failed: {}", e.getMessage());
        }
    }
}
