package com.campusconnect.feature.election;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.election.ClubElection;
import com.campusconnect.domain.election.ElectionBallot;
import com.campusconnect.domain.election.ElectionCandidate;
import com.campusconnect.domain.election.ElectionStatus;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.JsValues;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Elections as members see them (ElectionService viewOf). Vote counts appear only once voting has closed, so nothing
 * can be read from them while it's on; turnout ("12 of 30 voted") is visible meanwhile. Who voted for whom is never
 * known: a viewer only learns whether they themselves voted.
 */
@Component
public class ElectionViews {

    public record ElectionUser(@JsonProperty("_id") String id, String name, String avatar, String departmentCode, String batchCode) {
    }

    /** {@code votes} and {@code leading} are null/false until voting has closed. */
    public record CandidateView(ElectionUser user, String statement, boolean stillMember, Integer votes, boolean leading) {
    }

    public record ResultView(boolean tie, boolean noVotes, List<String> winners) {
    }

    public record ViewerView(boolean eligible, boolean hasVoted, boolean canVote, boolean canManage, boolean canRunoff) {
    }

    public record ElectionView(
            @JsonProperty("_id") String id,
            String club,
            String role,
            String roleName,
            String title,
            String description,
            ElectionStatus status,
            Instant opensAt,
            Instant closesAt,
            Integer eligibleCount,
            int votesCast,
            String runoffOf,
            ElectionUser createdBy,
            Instant createdAt,
            Instant closedAt,
            Instant cancelledAt,
            String cancelReason,
            List<CandidateView> candidates,
            ResultView result,
            ViewerView viewer) {
    }

    public record ElectionList(boolean canManage, List<ElectionView> items) {
    }

    /** Candidates by votes (stable, so ties keep the organiser's order), the top count and who has it. */
    record Standings(List<ElectionCandidate> sorted, int top, List<ElectionCandidate> leaders, boolean tie) {
    }

    private final MongoOperations mongo;
    private final ClubAccess clubAccess;

    public ElectionViews(MongoOperations mongo, ClubAccess clubAccess) {
        this.mongo = mongo;
        this.clubAccess = clubAccess;
    }

    static Standings standings(ClubElection election) {
        List<ElectionCandidate> sorted = new ArrayList<>(election.getCandidates());
        sorted.sort(Comparator.comparingInt(ElectionCandidate::getVotes).reversed());
        int top = sorted.isEmpty() ? 0 : sorted.get(0).getVotes();
        List<ElectionCandidate> leaders = top > 0 ? sorted.stream().filter(candidate -> candidate.getVotes() == top).toList() : List.of();
        return new Standings(sorted, top, leaders, leaders.size() > 1);
    }

    /** Who joined (or asked to join) when: the date that decides whether a member may vote. */
    static Instant joinedOf(ClubMembership membership) {
        if (membership == null) {
            return null;
        }
        return membership.getJoinedAt() != null ? membership.getJoinedAt() : membership.getCreatedAt();
    }

    /** Members who were in the club (approved) when voting opened. */
    static boolean isVoter(ClubMembership membership, ClubElection election) {
        Instant joined = joinedOf(membership);
        return membership != null && membership.getStatus() == MembershipStatus.APPROVED && joined != null && !joined.isAfter(election.getOpensAt());
    }

    static boolean canManage(ClubContext context) {
        return context.has(ClubPermission.RUN_ELECTIONS) && context.club().getStatus() == ClubStatus.ACTIVE;
    }

    private static ElectionUser userView(User user) {
        if (user == null) {
            return null;
        }
        return new ElectionUser(user.getId(), user.getName(), JsValues.truthy(user.getAvatar()) ? user.getAvatar() : null,
                JsValues.truthy(user.getDepartmentCode()) ? user.getDepartmentCode() : null, JsValues.truthy(user.getBatchCode()) ? user.getBatchCode() : null);
    }

    /** Several elections of one club for one viewer: three queries (people, members, the viewer's own ballots). */
    public List<ElectionView> viewOf(List<ClubElection> elections, AuthUser actor, ClubContext context) {
        if (elections.isEmpty()) {
            return List.of();
        }
        Set<ObjectId> ids = new LinkedHashSet<>();
        for (ClubElection election : elections) {
            election.getCandidates().forEach(candidate -> ids.add(new ObjectId(candidate.getUser())));
            if (election.getCreatedBy() != null) {
                ids.add(new ObjectId(election.getCreatedBy()));
            }
        }
        Query userQuery = Query.query(Criteria.where("_id").in(ids));
        userQuery.fields().include("name").include("avatar").include("departmentCode").include("batchCode");
        Map<String, User> users = new HashMap<>();
        mongo.find(userQuery, User.class).forEach(user -> users.put(user.getId(), user));
        Set<String> members = new HashSet<>(clubAccess.approvedMemberIds(elections.get(0).getClub()));
        Set<String> voted = Set.of();
        if (actor != null) {
            Query ballotQuery = Query.query(Criteria.where("voter").is(new ObjectId(actor.id()))
                    .and("election").in(elections.stream().map(election -> new ObjectId(election.getId())).toList()));
            ballotQuery.fields().include("election");
            voted = mongo.find(ballotQuery, ElectionBallot.class).stream().map(ElectionBallot::getElection).filter(Objects::nonNull).collect(Collectors.toSet());
        }
        boolean canManage = canManage(context);
        List<ElectionView> views = new ArrayList<>(elections.size());
        for (ClubElection election : elections) {
            views.add(view(election, users, members, voted.contains(election.getId()), isVoter(context.membership(), election), canManage));
        }
        return views;
    }

    public ElectionView viewOne(ClubElection election, AuthUser actor, ClubContext context) {
        return viewOf(List.of(election), actor, context).get(0);
    }

    private static ElectionView view(ClubElection election, Map<String, User> users, Set<String> members, boolean hasVoted, boolean eligible, boolean canManage) {
        boolean closed = election.getStatus() == ElectionStatus.CLOSED;
        Standings standings = standings(election);
        List<ElectionCandidate> leaders = closed ? standings.leaders() : List.of();
        boolean tie = closed && standings.tie();
        Set<String> leaderIds = leaders.stream().map(ElectionCandidate::getUser).collect(Collectors.toSet());
        // Counts only once voting has closed, so nothing can be read from them while it's on.
        List<CandidateView> candidates = (closed ? standings.sorted() : election.getCandidates()).stream()
                .map(candidate -> new CandidateView(userView(users.get(candidate.getUser())), candidate.getStatement(), members.contains(candidate.getUser()),
                        closed ? candidate.getVotes() : null, closed && leaderIds.contains(candidate.getUser())))
                .toList();
        boolean scheduled = election.getStatus() == ElectionStatus.SCHEDULED;
        return new ElectionView(
                election.getId(),
                election.getClub(),
                election.getRole(),
                election.getRoleName(),
                election.getTitle(),
                election.getDescription(),
                election.getStatus(),
                election.getOpensAt(),
                election.getClosesAt(),
                scheduled ? null : election.getEligibleCount(),
                scheduled ? 0 : election.getVotesCast(),
                election.getRunoffOf(),
                userView(users.get(election.getCreatedBy())),
                election.getCreatedAt(),
                election.getClosedAt(),
                election.getCancelledAt(),
                election.getCancelReason() == null ? "" : election.getCancelReason(),
                candidates,
                closed ? new ResultView(tie, leaders.isEmpty(), leaders.stream().map(ElectionCandidate::getUser).toList()) : null,
                new ViewerView(eligible, hasVoted, election.getStatus() == ElectionStatus.OPEN && eligible && !hasVoted, canManage, canManage && closed && tie));
    }
}
