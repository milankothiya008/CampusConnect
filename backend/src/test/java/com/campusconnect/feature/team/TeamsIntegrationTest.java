package com.campusconnect.feature.team;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamMember;
import com.campusconnect.domain.event.TeamMemberStatus;
import com.campusconnect.feature.event.EventFlows;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Port of tests/integration/teams.test.js ("team registration"), plus a concurrent-accept check. */
class TeamsIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser coordinator;
    TestUser asha;
    TestUser bina;
    TestUser chirag;
    TestUser dev;
    TestUser esha;
    TestUser farah;
    TestUser gita;
    TestUser hari;
    TestUser itStudent;
    Club club;
    String eventId;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty();
        president = data.student(spec -> spec.name("President"));
        coordinator = data.student(spec -> spec.name("Coordinator"));
        asha = named("Asha Student");
        bina = named("Bina Student");
        chirag = named("Chirag Student");
        dev = named("Dev Student");
        esha = named("Esha Student");
        farah = named("Farah Student");
        gita = named("Gita Student");
        hari = named("Hari Student");
        itStudent = data.student(spec -> spec.name("Ira Other").department("IT"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        eventId = flows.published(president, mentor, club, venues.hall(), "title", "Hack Relay", "participationMode", "TEAM", "minTeamSize", 2, "maxTeamSize", 3,
                "maxParticipants", 2, "eligibility", map("departments", List.of("CE"), "batches", List.of()), "eventDate", TestData.futureDate(10));
    }

    TestUser named(String name) {
        return data.student(spec -> spec.name(name));
    }

    Response register(TestUser user, Map<String, Object> body) {
        return flows.register(user, eventId, body);
    }

    String statusOf(TestUser user) {
        return flows.statusOf(user, eventId);
    }

    Team teamOf(String name) {
        return mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("nameKey").is(name.toLowerCase()).and("status").is("ACTIVE")), Team.class);
    }

    Response accept(TestUser user, Team team) {
        return api.as(user).post("/api/events/" + eventId + "/teams/" + team.getId() + "/accept");
    }

    // ---------------------------------------------------------------- the story so far (each test starts from scratch)

    void byteBustersRegistered() {
        assertThat(register(asha, map("teamName", "Byte Busters", "invitees", List.of(bina.id(), chirag.id()))).status()).isEqualTo(201);
    }

    void binaJoinedChiragDeclined() {
        byteBustersRegistered();
        Team team = teamOf("Byte Busters");
        accept(bina, team);
        api.as(chirag).post("/api/events/" + eventId + "/teams/" + team.getId() + "/decline");
    }

    void devJoined() {
        binaJoinedChiragDeclined();
        api.as(asha).post("/api/events/" + eventId + "/team/invites", map("users", List.of(dev.id())));
        accept(dev, teamOf("Byte Busters"));
    }

    void devLeft() {
        devJoined();
        flows.cancel(dev, eventId);
    }

    void lateNightMovedIn() {
        devLeft();
        register(esha, map("teamName", "Null Pointers"));
        register(farah, map("teamName", "Late Night", "invitees", List.of(gita.id())));
        accept(gita, teamOf("Late Night"));
        flows.cancel(asha, eventId);
    }

    // ---------------------------------------------------------------- the original tests

    @Test
    void teamSettingsAreValidatedAndTheLimitCountsFullTeamsAgainstTheVenue() {
        Response bad = flows.create(president, club, venues.hall(), "participationMode", "TEAM", "minTeamSize", 4, "maxTeamSize", 3, "eventDate", TestData.futureDate(11));
        assertThat(bad.status()).isEqualTo(400);
        Response tooBig = flows.create(president, club, venues.hall(), "participationMode", "TEAM", "minTeamSize", 2, "maxTeamSize", 4, "maxParticipants", 20,
                "eventDate", TestData.futureDate(11));
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(tooBig.message()).isEqualTo("20 teams of up to 4 would exceed Seminar Hall A's capacity of 60");

        Event event = flows.event(eventId);
        assertThat(event.getParticipationMode().name()).isEqualTo("TEAM");
        assertThat(event.getMinTeamSize()).isEqualTo(2);
        assertThat(event.getMaxTeamSize()).isEqualTo(3);
        assertThat(event.getMaxParticipants()).isEqualTo(2);
    }

    @Test
    void aTeamEventNeedsATeamName() {
        Response res = register(asha, map());
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.message()).isEqualTo("Team name must be 2-60 characters");
        assertThat(statusOf(asha)).isNull();
    }

    @Test
    void inviteesMustBeEligibleStudentsWithRoomOnTheTeam() {
        assertThat(register(asha, map("teamName", "Byte Busters", "invitees", List.of(itStudent.id()))).status()).isEqualTo(403);
        assertThat(register(asha, map("teamName", "Byte Busters", "invitees", List.of(asha.id()))).status()).isEqualTo(400);
        Response crowd = register(asha, map("teamName", "Byte Busters", "invitees", List.of(bina.id(), chirag.id(), dev.id())));
        assertThat(crowd.status()).isEqualTo(400);
        assertThat(crowd.message()).isEqualTo("Your team has room for 2 more invites (teams have up to 3 members)");
        // Nothing was registered by the failed attempts.
        assertThat(statusOf(asha)).isNull();
        assertThat(mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId))), Team.class)).isZero();
    }

    @Test
    void theLeaderRegistersTheTeamWhichTakesOnePlaceAndInvitesTeammates() {
        Response res = register(asha, map("teamName", "Byte Busters", "invitees", List.of(bina.id(), chirag.id())));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.message()).isEqualTo("Team \"Byte Busters\" is registered. Invites sent to 2 teammates.");
        assertThat(res.string("$.data.team.name")).isEqualTo("Byte Busters");
        assertThat((Integer) res.at("$.data.team.size")).isEqualTo(1);
        assertThat((Integer) res.at("$.data.team.minSize")).isEqualTo(2);
        assertThat((Integer) res.at("$.data.team.maxSize")).isEqualTo(3);
        assertThat((Boolean) res.at("$.data.team.complete")).isFalse();
        assertThat(res.string("$.data.team.registrationStatus")).isEqualTo("REGISTERED");
        assertThat((List<String>) res.at("$.data.team.invites[*].user.name")).containsExactlyInAnyOrder("Bina Student", "Chirag Student");
        assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(1);

        assertThat(flows.notified(bina, "TEAM_INVITE", "Asha Student invited you to join \"Byte Busters\" for Hack Relay")).isTrue();

        Response binaView = api.as(bina).get("/api/events/" + eventId);
        assertThat((List<?>) binaView.at("$.data.viewer.invites")).hasSize(1);
        assertThat(binaView.string("$.data.viewer.invites[0].team.name")).isEqualTo("Byte Busters");
        assertThat(binaView.string("$.data.viewer.invites[0].team.leader.name")).isEqualTo("Asha Student");

        Response inbox = api.as(bina).get("/api/registrations/invites");
        assertThat((List<String>) inbox.at("$.data[*].team.name")).containsExactly("Byte Busters");
        assertThat((List<String>) inbox.at("$.data[*].event.title")).containsExactly("Hack Relay");
    }

    @Test
    void teamNamesAreUniqueWithinTheEventAndAFailedRegistrationLeavesNothingBehind() {
        byteBustersRegistered();
        Response clash = register(dev, map("teamName", "byte  BUSTERS"));
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.message()).contains("already registered for this event — pick another name");
        assertThat(statusOf(dev)).isNull();
        assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(1);
    }

    @Test
    void theLeaderCanSearchEligibleStudentsToInvite() {
        byteBustersRegistered();
        Response found = api.as(asha).get("/api/events/" + eventId + "/team/candidates?search=Student");
        List<String> names = found.at("$.data[*].name");
        assertThat(names).contains("Dev Student").doesNotContain("Asha Student", "Ira Other");
        List<Map<String, Object>> devRow = found.at("$.data[?(@.name == 'Dev Student')]");
        assertThat(devRow.get(0).get("available")).isEqualTo(true);
    }

    @Test
    void inviteesAcceptRegisteringWithTheTeamOrDeclineTheLeaderHearsAboutBoth() {
        byteBustersRegistered();
        Team team = teamOf("Byte Busters");
        Response accepted = accept(bina, team);
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.message()).isEqualTo("You joined the team and are registered");
        assertThat((Integer) accepted.at("$.data.team.size")).isEqualTo(2);
        assertThat((Boolean) accepted.at("$.data.team.complete")).isTrue();
        assertThat(statusOf(bina)).isEqualTo("REGISTERED");
        assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(1);
        assertThat(flows.notified(asha, "TEAM_UPDATE", "Bina Student joined \"Byte Busters\"")).isTrue();

        Response declined = api.as(chirag).post("/api/events/" + eventId + "/teams/" + team.getId() + "/decline");
        assertThat(declined.status()).isEqualTo(200);
        assertThat(statusOf(chirag)).isNull();
        assertThat(flows.notifiedTitled(asha, "Chirag Student declined your invite to \"Byte Busters\"")).isTrue();

        // Answered invites can't be used again, and a teammate can't also register separately.
        assertThat(accept(chirag, team).status()).isEqualTo(404);
        assertThat(register(bina, map("teamName", "Another")).status()).isEqualTo(409);
    }

    @Test
    void onlyTheLeaderManagesTheTeamTheTeamNeverGoesOverItsSize() {
        binaJoinedChiragDeclined();
        Team team = teamOf("Byte Busters");
        assertThat(api.as(bina).post("/api/events/" + eventId + "/team/invites", map("users", List.of(dev.id()))).status()).isEqualTo(403);

        Response invited = api.as(asha).post("/api/events/" + eventId + "/team/invites", map("users", List.of(dev.id())));
        assertThat(invited.status()).isEqualTo(200);
        assertThat((List<String>) invited.at("$.data.invites[*].user.name")).containsExactly("Dev Student");
        // 2 members + 1 pending invite = the maximum of 3.
        assertThat(api.as(asha).post("/api/events/" + eventId + "/team/invites", map("users", List.of(esha.id()))).status()).isEqualTo(400);

        assertThat(accept(dev, team).status()).isEqualTo(200);
        Response view = api.as(asha).get("/api/events/" + eventId);
        assertThat(view.string("$.data.viewer.teamRole")).isEqualTo("LEADER");
        assertThat((Integer) view.at("$.data.viewer.team.size")).isEqualTo(3);
        assertThat((Boolean) view.at("$.data.viewer.team.complete")).isTrue();
        assertThat((List<String>) view.at("$.data.viewer.team.members[*].status")).containsExactly("LEADER", "ACCEPTED", "ACCEPTED");
    }

    @Test
    void organisersSeeTheEntriesGroupedByTeam() {
        devJoined();
        Response res = api.as(coordinator).get("/api/events/" + eventId + "/registrations");
        assertThat(res.string("$.meta.event.participationMode")).isEqualTo("TEAM");
        assertThat((Integer) res.at("$.meta.event.minTeamSize")).isEqualTo(2);
        assertThat((Integer) res.at("$.meta.event.maxTeamSize")).isEqualTo(3);
        assertThat((Integer) res.at("$.meta.event.registeredCount")).isEqualTo(1);
        assertThat((List<String>) res.at("$.meta.teams[*].name")).containsExactly("Byte Busters");
        assertThat((List<Integer>) res.at("$.meta.teams[*].size")).containsExactly(3);
        assertThat((List<Boolean>) res.at("$.meta.teams[*].complete")).containsExactly(true);
        assertThat((List<String>) res.at("$.data[*].user.name")).containsExactly("Asha Student", "Bina Student", "Dev Student");
        assertThat((List<String>) res.at("$.data[*].team.name")).containsExactly("Byte Busters", "Byte Busters", "Byte Busters");
        assertThat((List<String>) res.at("$.data[*].teamRole")).containsExactly("LEADER", "MEMBER", "MEMBER");

        Response mine = api.as(bina).get("/api/registrations/me");
        assertThat(mine.string("$.data[0].teamRole")).isEqualTo("MEMBER");
        assertThat(mine.string("$.data[0].team.name")).isEqualTo("Byte Busters");
        assertThat(mine.string("$.data[0].event.participationMode")).isEqualTo("TEAM");
    }

    @Test
    void aMemberCanLeaveWithoutAffectingTheTeamsPlace() {
        devJoined();
        Response res = flows.cancel(dev, eventId);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.message()).isEqualTo("You left the team");
        assertThat(statusOf(dev)).isEqualTo("CANCELLED");
        assertThat(teamOf("Byte Busters").getSize()).isEqualTo(2);
        assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(1);
        assertThat(flows.notifiedTitled(asha, "Dev Student left \"Byte Busters\"")).isTrue();
    }

    @Test
    void whenTheEventIsFullWholeTeamsWaitInLineAndMoveInTogether() {
        devLeft();
        Response second = register(esha, map("teamName", "Null Pointers"));
        assertThat((Boolean) second.at("$.data.waitlisted")).isFalse();
        assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(2);

        Response third = register(farah, map("teamName", "Late Night", "invitees", List.of(gita.id())));
        assertThat(third.status()).isEqualTo(201);
        assertThat(third.message()).isEqualTo("This event is full. Your team is #1 on the waitlist. Invites sent to 1 teammate.");
        Team lateTeam = teamOf("Late Night");
        Response joined = accept(gita, lateTeam);
        assertThat(joined.message()).isEqualTo("You joined the team. The team is on the waitlist.");
        assertThat(statusOf(gita)).isEqualTo("WAITLISTED");
        assertThat(flows.event(eventId).getWaitlistCount()).isEqualTo(1);
        assertThat((Integer) api.as(gita).get("/api/events/" + eventId).at("$.data.viewer.registration.waitlistPosition")).isEqualTo(1);

        // The first team's leader cancels: the team is disbanded and the waiting team moves in together.
        Response cancelled = flows.cancel(asha, eventId);
        assertThat(cancelled.message()).isEqualTo("Your team's registration was cancelled");
        assertThat(statusOf(bina)).isEqualTo("CANCELLED");
        assertThat(flows.notifiedTitled(bina, "\"Byte Busters\" is no longer registered for Hack Relay")).isTrue();
        assertThat(statusOf(farah)).isEqualTo("REGISTERED");
        assertThat(statusOf(gita)).isEqualTo("REGISTERED");
        assertThat(flows.notifiedTitled(gita, "You're in! A spot opened up for Hack Relay")).isTrue();
        Event event = flows.event(eventId);
        assertThat(event.getRegisteredCount()).isEqualTo(2);
        assertThat(event.getWaitlistCount()).isZero();
    }

    @Test
    void organisersCanRemoveAMemberTheTeamKeepsItsPlaceOrAWholeTeam() {
        lateNightMovedIn();
        Team lateTeam = teamOf("Late Night");
        EventRegistration gitaRegistration = flows.registrationOf(gita, eventId);
        assertThat(api.as(coordinator).delete("/api/events/" + eventId + "/registrations/" + gitaRegistration.getId(), map("reason", "Duplicate entry")).status())
                .isEqualTo(200);
        assertThat(statusOf(gita)).isEqualTo("CANCELLED");
        assertThat(mongo.findById(lateTeam.getId(), Team.class).getSize()).isEqualTo(1);
        assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(2);

        api.as(esha).post("/api/events/" + eventId + "/team/invites", map("users", List.of(hari.id())));
        Team nullPointers = teamOf("Null Pointers");
        accept(hari, nullPointers);
        EventRegistration leaderRegistration = flows.registrationOf(esha, eventId);
        assertThat(api.as(coordinator).delete("/api/events/" + eventId + "/registrations/" + leaderRegistration.getId(), map("reason", "Rules broken")).status())
                .isEqualTo(200);
        assertThat(statusOf(hari)).isEqualTo("CANCELLED");
        assertThat(mongo.findById(nullPointers.getId(), Team.class).getStatus().name()).isEqualTo("DISBANDED");
        assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(1);
    }

    @Test
    void teamsCantChangeAfterRegistrationCloses() {
        lateNightMovedIn();
        flows.set(eventId, new Update().set("registrationEnd", Instant.now().minusSeconds(1)).set("registrationStart", Instant.now().minus(Duration.ofDays(1))));
        Response res = api.as(farah).post("/api/events/" + eventId + "/team/invites", map("users", List.of(chirag.id())));
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.message()).isEqualTo("Registration has closed, so teams can no longer change");
    }

    // ---------------------------------------------------------------- beyond the original suite

    @Test
    void invitedStudentsAcceptingTheLastSpotsAtOnceNeverOverfillTheTeam() throws Exception {
        byteBustersRegistered();
        Team team = teamOf("Byte Busters");
        // Two more invites than the team has room for (written directly; the API never sends that many).
        List<TestUser> invitees = List.of(dev, esha, farah, gita);
        List<TeamMember> extra = invitees.stream().map(user -> {
            TeamMember member = new TeamMember();
            member.setUser(user.id());
            member.setStatus(TeamMemberStatus.INVITED);
            member.setInvitedAt(Instant.now());
            return member;
        }).toList();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(team.getId()))), new Update().push("members").each(extra.toArray()), Team.class);

        List<Callable<Response>> accepts = new ArrayList<>();
        for (TestUser user : List.of(bina, chirag, dev, esha, farah, gita)) {
            accepts.add(() -> accept(user, team));
        }
        List<Response> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(accepts.size())) {
            for (Future<Response> result : pool.invokeAll(accepts)) {
                results.add(result.get());
            }
        }
        assertThat(results.stream().filter(res -> res.status() == 200)).hasSize(2);
        assertThat(results.stream().filter(res -> res.status() == 409)).allSatisfy(res -> assertThat(res.message()).isEqualTo("\"Byte Busters\" is already full"));
        Team after = mongo.findById(team.getId(), Team.class);
        assertThat(after.getSize()).isEqualTo(3);
        assertThat(after.getMembers().stream().filter(member -> member.getStatus() == TeamMemberStatus.ACCEPTED)).hasSize(2);
        assertThat(mongo.count(Query.query(Criteria.where("team").is(new ObjectId(team.getId())).and("teamRole").is("MEMBER").and("status").is("REGISTERED")),
                EventRegistration.class)).isEqualTo(2);
    }
}
