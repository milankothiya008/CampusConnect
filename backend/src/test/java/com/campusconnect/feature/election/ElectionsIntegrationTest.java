package com.campusconnect.feature.election;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubRole;
import com.campusconnect.domain.election.ClubElection;
import com.campusconnect.domain.election.ElectionBallot;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Port of tests/integration/elections.test.js. */
class ElectionsIntegrationTest extends IntegrationTest {

    @Autowired
    ElectionLifecycle lifecycle;

    static String inMinutes(long minutes) {
        return Instant.now().plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS).toString();
    }

    static Map<String, Object> candidate(TestUser user) {
        return Map.of("user", user.id());
    }

    /** Anonymous club elections: the organiser picks candidates, members vote once in secret, the result is advisory. */
    @Nested
    class ClubElections {

        Club club;
        TestUser mentor;
        TestUser president;
        TestUser vp;
        TestUser asha;
        TestUser bina;
        TestUser chirag;
        TestUser outsider;

        @BeforeEach
        void club() {
            president = data.student(s -> s.name("Prez"));
            vp = data.student(s -> s.name("Vee"));
            asha = data.student(s -> s.name("Asha"));
            bina = data.student(s -> s.name("Bina"));
            chirag = data.student(s -> s.name("Chirag"));
            outsider = data.student(s -> s.name("Out"));
            mentor = data.faculty("CE", "Dr Mentor");
            club = data.activeClub("Coding Club", mentor, president);
            data.addMembership(club, vp, "VICE_PRESIDENT");
            for (TestUser student : List.of(asha, bina, chirag)) {
                data.addMembership(club, student);
            }
        }

        Response create(TestUser user, Map<String, Object> body) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("club", club.getId());
            payload.putAll(body);
            return api.as(user).post("/api/elections", payload);
        }

        /** The president lets the vice-president run elections too (what PUT /api/clubs/:id/roles/VICE_PRESIDENT does). */
        void grantVicePresidentElections() {
            List<ClubRole> roles = new ArrayList<>(ClubRoles.defaultRoles());
            ClubRole vpRole = roles.stream().filter(role -> role.getKey().equals("VICE_PRESIDENT")).findFirst().orElseThrow();
            List<String> permissions = new ArrayList<>(vpRole.getPermissions());
            permissions.add("RUN_ELECTIONS");
            vpRole.setPermissions(permissions);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(club.getId()))), new Update().set("roles", roles), Club.class);
        }

        boolean notified(TestUser user, String titleFragment) {
            Criteria criteria = Criteria.where("user").is(new ObjectId(user.id())).and("type").is("ELECTION");
            if (titleFragment != null) {
                criteria = criteria.and("title").regex(titleFragment);
            }
            return mongo.exists(Query.query(criteria), Notification.class);
        }

        Response vote(TestUser user, String electionId, TestUser choice) {
            return api.as(user).post("/api/elections/" + electionId + "/vote", Map.of("candidate", choice.id()));
        }

        @Test
        void onlyRolesWithRunElectionsCanHoldOneItIsForRealRolesAndNeedsTwoOrMoreMemberCandidates() {
            Map<String, Object> base = Map.of("role", "VICE_PRESIDENT", "candidates", List.of(candidate(asha), candidate(bina)), "closesAt", inMinutes(60));
            assertThat(create(asha, base).status()).isEqualTo(403);
            assertThat(create(vp, base).status()).isEqualTo(403);
            assertThat(create(president, with(base, "role", "MEMBER")).status()).isEqualTo(400);
            assertThat(create(president, with(base, "candidates", List.of(candidate(asha)))).status()).isEqualTo(400);
            assertThat(create(president, with(base, "candidates", List.of(candidate(asha), candidate(outsider)))).message()).contains("member");
            assertThat(create(president, with(base, "closesAt", inMinutes(3))).status()).isEqualTo(400);

            // The president can let the vice-president run elections too.
            grantVicePresidentElections();
            assertThat(create(vp, base).status()).isEqualTo(201);
        }

        @Test
        void membersVoteOnceInSecretCountsStayHiddenUntilVotingCloses() {
            grantVicePresidentElections();
            Response res = create(vp, Map.of(
                    "role", "VICE_PRESIDENT",
                    "title", "Next vice-president",
                    "candidates", List.of(Map.of("user", asha.id(), "statement", "Ran the tech fest"), candidate(bina)),
                    "closesAt", inMinutes(60)));
            assertThat(res.status()).isEqualTo(201);
            assertThat(res.message()).isEqualTo("Election created");
            String electionId = res.string("$.data._id");
            assertThat(res.string("$.data.status")).isEqualTo("OPEN");
            assertThat((Integer) res.at("$.data.eligibleCount")).isEqualTo(5);
            assertThat((List<Object>) res.at("$.data.candidates[*].votes")).containsExactly(null, null);

            // A second election for the same role can't run at the same time.
            assertThat(create(president, Map.of("role", "VICE_PRESIDENT", "candidates", List.of(candidate(asha), candidate(chirag)), "closesAt", inMinutes(60))).status())
                    .isEqualTo(409);

            // The card is in the club group, and only the club's voters were notified.
            Conversation conversation = mongo.findOne(Query.query(Criteria.where("club").is(new ObjectId(club.getId()))), Conversation.class);
            assertThat(mongo.exists(Query.query(Criteria.where("conversation").is(new ObjectId(conversation.getId())).and("type").is("POLL")
                    .and("poll").is(new ObjectId(electionId))), Message.class)).isTrue();
            assertThat(notified(chirag, null)).isTrue();
            assertThat(notified(outsider, null)).isFalse();
            assertThat(notified(mentor, null)).isFalse();

            // Outsiders can't see it; the mentor can follow along but not vote.
            assertThat(api.as(outsider).get("/api/elections/" + electionId).status()).isEqualTo(404);
            Response mentorView = api.as(mentor).get("/api/elections/" + electionId);
            assertThat((Boolean) mentorView.at("$.data.viewer.eligible")).isFalse();
            assertThat((Boolean) mentorView.at("$.data.viewer.canVote")).isFalse();
            assertThat(vote(mentor, electionId, asha).status()).isEqualTo(403);

            // Someone who joined after voting opened isn't on the voter roll.
            TestUser late = data.student(s -> s.name("Late"));
            data.addMembership(club, late);
            assertThat(vote(late, electionId, asha).status()).isEqualTo(403);

            assertThat(vote(chirag, electionId, outsider).status()).isEqualTo(400);
            Response voted = vote(chirag, electionId, asha);
            assertThat(voted.status()).isEqualTo(200);
            assertThat(voted.message()).isEqualTo("Vote recorded");
            assertThat((Boolean) voted.at("$.data.viewer.hasVoted")).isTrue();
            assertThat((Boolean) voted.at("$.data.viewer.canVote")).isFalse();
            assertThat((Integer) voted.at("$.data.votesCast")).isEqualTo(1);
            assertThat((List<Object>) voted.at("$.data.candidates[*].votes")).containsOnlyNulls();
            assertThat(vote(chirag, electionId, bina).status()).isEqualTo(409);

            vote(asha, electionId, asha);
            vote(bina, electionId, asha);
            vote(president, electionId, bina);

            // Only *that* someone voted is stored — never their choice.
            List<Document> ballots = mongo.find(Query.query(Criteria.where("election").is(new ObjectId(electionId))), Document.class, "electionballots");
            assertThat(ballots).hasSize(4);
            ballots.forEach(ballot -> assertThat(ballot.keySet()).containsExactlyInAnyOrder("_id", "election", "voter"));

            // Members can't end it; the organiser can close early, and then the counts are shown.
            assertThat(api.as(asha).post("/api/elections/" + electionId + "/close").status()).isEqualTo(403);
            Response closed = api.as(vp).post("/api/elections/" + electionId + "/close");
            assertThat(closed.status()).isEqualTo(200);
            assertThat(closed.string("$.data.status")).isEqualTo("CLOSED");
            assertThat((List<String>) closed.at("$.data.candidates[*].user.name")).containsExactly("Asha", "Bina");
            assertThat((List<Integer>) closed.at("$.data.candidates[*].votes")).containsExactly(3, 1);
            assertThat((List<Boolean>) closed.at("$.data.candidates[*].leading")).containsExactly(true, false);
            assertThat((Boolean) closed.at("$.data.result.tie")).isFalse();
            assertThat((List<String>) closed.at("$.data.result.winners")).containsExactly(asha.id());
            assertThat(vote(chirag, electionId, bina).status()).isEqualTo(409);

            // The result is announced to the club and its mentor; the role itself is still the president's call.
            assertThat(notified(mentor, "Results")).isTrue();
            assertThat(mongo.exists(Query.query(Criteria.where("conversation").is(new ObjectId(conversation.getId())).and("type").is("SYSTEM")
                    .and("system.action").is("ELECTION_CLOSED")), Message.class)).isTrue();
            ClubMembership ashaMembership = mongo.findOne(Query.query(Criteria.where("club").is(new ObjectId(club.getId())).and("user").is(new ObjectId(asha.id()))),
                    ClubMembership.class);
            assertThat(ashaMembership.getRole()).isEqualTo("MEMBER");
        }

        @Test
        void aScheduledElectionCanBeEditedUntilItOpensTheSweeperOpensAndClosesItATieCanGoToARunoff() {
            Response res = create(president, Map.of("role", "TREASURER", "candidates", List.of(candidate(asha), candidate(bina)), "opensAt", inMinutes(30),
                    "closesAt", inMinutes(120)));
            assertThat(res.string("$.data.status")).isEqualTo("SCHEDULED");
            String id = res.string("$.data._id");
            assertThat(vote(asha, id, bina).status()).isEqualTo(409);

            Response edited = api.as(president).patch("/api/elections/" + id, Map.of("candidates", List.of(candidate(asha), candidate(chirag))));
            assertThat((List<String>) edited.at("$.data.candidates[*].user.name")).containsExactly("Asha", "Chirag");

            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(id))), new Update().set("opensAt", Instant.now()), ClubElection.class);
            lifecycle.sweep();
            assertThat(mongo.findById(id, ClubElection.class).getStatus().name()).isEqualTo("OPEN");
            assertThat(api.as(president).patch("/api/elections/" + id, Map.of("title", "Too late")).status()).isEqualTo(409);

            assertThat(vote(asha, id, asha).status()).isEqualTo(200);
            assertThat(vote(bina, id, chirag).status()).isEqualTo(200);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(id))), new Update().set("closesAt", Instant.now().minusSeconds(1)), ClubElection.class);
            lifecycle.sweep();

            Response tied = api.as(asha).get("/api/elections/" + id);
            assertThat(tied.string("$.data.status")).isEqualTo("CLOSED");
            assertThat((Boolean) tied.at("$.data.result.tie")).isTrue();
            assertThat((Boolean) tied.at("$.data.viewer.canRunoff")).isFalse();
            assertThat((Boolean) api.as(president).get("/api/elections/" + id).at("$.data.viewer.canRunoff")).isTrue();

            Response runoff = api.as(president).post("/api/elections/" + id + "/runoff", Map.of("closesAt", inMinutes(60)));
            assertThat(runoff.status()).isEqualTo(201);
            assertThat(runoff.message()).isEqualTo("Runoff created");
            assertThat(runoff.string("$.data.runoffOf")).isEqualTo(id);
            assertThat((List<String>) runoff.at("$.data.candidates[*].user.name")).containsExactlyInAnyOrder("Asha", "Chirag");

            // Cancelling tells the voters; the list shows everything the club has held.
            Response cancelled = api.as(president).post("/api/elections/" + runoff.string("$.data._id") + "/cancel", Map.of("reason", "Decided at the meeting"));
            assertThat(cancelled.string("$.data.status")).isEqualTo("CANCELLED");
            assertThat(notified(chirag, "Cancelled")).isTrue();
            Response list = api.as(chirag).get("/api/elections?club=" + club.getId());
            // The original suite shares one database across its tests, so its list also held the previous test's election.
            assertThat((List<String>) list.at("$.data.items[*].status")).containsExactly("CANCELLED", "CLOSED");
            assertThat((Boolean) list.at("$.data.canManage")).isFalse();
            assertThat(api.as(outsider).get("/api/elections?club=" + club.getId()).status()).isEqualTo(404);
        }

        @Test
        void parallelVotesFromOneMemberCountOnceAndLeaveOneBallot() throws Exception {
            String id = create(president, Map.of("role", "TREASURER", "candidates", List.of(candidate(asha), candidate(bina)), "closesAt", inMinutes(60)))
                    .string("$.data._id");
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                TestUser choice = i % 2 == 0 ? asha : bina;
                attempts.add(() -> vote(chirag, id, choice).status());
            }
            List<Integer> statuses = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
                for (Future<Integer> result : pool.invokeAll(attempts)) {
                    statuses.add(result.get());
                }
            }
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(11);
            assertThat(mongo.count(Query.query(Criteria.where("election").is(new ObjectId(id))), ElectionBallot.class)).isEqualTo(1);
            ClubElection stored = mongo.findById(id, ClubElection.class);
            assertThat(stored.getVotesCast()).isEqualTo(1);
            assertThat(stored.getCandidates().stream().mapToInt(candidate -> candidate.getVotes()).sum()).isEqualTo(1);
        }

        @Test
        void electionViewsHaveTheOriginalShape() {
            Response open = create(president, Map.of("role", "TREASURER", "candidates", List.of(candidate(asha), candidate(bina)), "closesAt", inMinutes(60)));
            Map<String, Object> view = open.at("$.data");
            assertThat(view.keySet()).containsExactly("_id", "club", "role", "roleName", "title", "description", "status", "opensAt", "closesAt", "eligibleCount",
                    "votesCast", "runoffOf", "createdBy", "createdAt", "closedAt", "cancelledAt", "cancelReason", "candidates", "result", "viewer");
            assertThat(((Map<String, Object>) open.at("$.data.createdBy")).keySet()).containsExactly("_id", "name", "avatar", "departmentCode", "batchCode");
            assertThat(((Map<String, Object>) open.at("$.data.candidates[0]")).keySet()).containsExactly("user", "statement", "stillMember", "votes", "leading");
            assertThat(((Map<String, Object>) open.at("$.data.viewer")).keySet()).containsExactly("eligible", "hasVoted", "canVote", "canManage", "canRunoff");
            assertThat((Object) open.at("$.data.result")).isNull();
            assertThat(open.string("$.data.roleName")).isEqualTo("Treasurer");
            assertThat(open.string("$.data.title")).isEqualTo("Treasurer election");

            Response closed = api.as(president).post("/api/elections/" + open.string("$.data._id") + "/close");
            assertThat(((Map<String, Object>) closed.at("$.data.result")).keySet()).containsExactly("tie", "noVotes", "winners");
            assertThat((Boolean) closed.at("$.data.result.noVotes")).isTrue();
            assertThat(((Map<String, Object>) api.as(asha).get("/api/elections?club=" + club.getId()).at("$.data")).keySet()).containsExactly("canManage", "items");

            Response scheduled = create(president, Map.of("role", "TREASURER", "candidates", List.of(candidate(asha), candidate(bina)), "opensAt", inMinutes(30),
                    "closesAt", inMinutes(90)));
            // Before voting opens the roll isn't fixed yet.
            assertThat((Object) scheduled.at("$.data.eligibleCount")).isNull();
            assertThat((Integer) scheduled.at("$.data.votesCast")).isZero();
        }

        @Test
        void validationMessagesMatchTheOriginalRules() {
            Response missing = api.as(president).post("/api/elections", Map.of());
            assertThat(missing.status()).isEqualTo(400);
            assertThat(missing.errorCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(missing.message()).isEqualTo("Choose a club. Choose the role this election is for. Choose 2 to 10 candidates. Choose when voting closes");
            assertThat(create(president, Map.of("role", "TREASURER", "candidates", List.of(candidate(asha), candidate(bina)), "closesAt", "tomorrow")).message())
                    .isEqualTo("Choose when voting closes");
            assertThat(api.as(president).get("/api/elections/not-an-id").message()).isEqualTo("Invalid election");
            assertThat(api.as(president).get("/api/elections").message()).isEqualTo("Choose a club");
            assertThat(api.as(president).patch("/api/elections/" + new ObjectId().toHexString(), Map.of("title", 5, "opensAt", "soon")).message())
                    .isEqualTo("Invalid value. Choose when voting opens");
            assertThat(api.as(president).post("/api/elections/" + new ObjectId().toHexString() + "/vote", Map.of()).message()).isEqualTo("Choose a candidate");
        }
    }

    static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
