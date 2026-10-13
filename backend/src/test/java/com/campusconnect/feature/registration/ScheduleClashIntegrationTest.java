package com.campusconnect.feature.registration;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.feature.event.EventFlows;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of tests/integration/scheduleClash.test.js: a student can't hold places at two events at the same time;
 * they can switch while the other hasn't started.
 */
class ScheduleClashIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser asha;
    TestUser bina;
    TestUser chirag;
    TestUser dev;
    Club club;
    String a;
    String b;
    String c;
    String t;
    String i;
    String w;
    String x;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty("CE", "Dr Mentor");
        president = data.student(spec -> spec.name("President"));
        asha = data.student(spec -> spec.name("Asha"));
        bina = data.student(spec -> spec.name("Bina"));
        chirag = data.student(spec -> spec.name("Chirag"));
        dev = data.student(spec -> spec.name("Dev"));
        club = data.activeClub("Coding Club", mentor, president);
        // Day 10: A 10–13 (one seat), B 11–14, C 13–15 (right after A).
        a = publish(venues.auditorium(), "title", "Workshop A", "eventDate", TestData.futureDate(10), "startTime", "10:00", "endTime", "13:00", "maxParticipants", 1);
        b = publish(venues.hall(), "title", "Talk B", "eventDate", TestData.futureDate(10), "startTime", "11:00", "endTime", "14:00");
        c = publish(venues.auditorium(), "title", "Quiz C", "eventDate", TestData.futureDate(10), "startTime", "13:00", "endTime", "15:00");
        // Day 12: team event T 10–13 and individual I 11–12.
        t = publish(venues.auditorium(), "title", "Team Hack T", "eventDate", TestData.futureDate(12), "startTime", "10:00", "endTime", "13:00",
                "participationMode", "TEAM", "minTeamSize", 1, "maxTeamSize", 3);
        i = publish(venues.hall(), "title", "Seminar I", "eventDate", TestData.futureDate(12), "startTime", "11:00", "endTime", "12:00");
        // Day 15: W 10–12 (one seat) and X 10–12.
        w = publish(venues.auditorium(), "title", "Lab W", "eventDate", TestData.futureDate(15), "startTime", "10:00", "endTime", "12:00", "maxParticipants", 1);
        x = publish(venues.hall(), "title", "Lab X", "eventDate", TestData.futureDate(15), "startTime", "10:00", "endTime", "12:00");
    }

    String publish(Venue venue, Object... overrides) {
        return flows.published(president, mentor, club, venue, overrides);
    }

    Response register(TestUser student, String eventId, Object... body) {
        return flows.register(student, eventId, map(body));
    }

    void ashaHoldsAAndC() {
        register(asha, a);
        register(bina, a);
        register(asha, c);
    }

    void ashaSwitchedToB() {
        ashaHoldsAAndC();
        assertThat(register(asha, b, "replace", List.of(a, c)).status()).isEqualTo(201);
    }

    @Test
    void anOverlappingRegistrationIsRefusedWithTheClashingEventBackToBackEventsAreFine() {
        assertThat(register(asha, a).status()).isEqualTo(201);
        assertThat((Boolean) register(bina, a).at("$.data.waitlisted")).isTrue();

        Response refused = register(asha, b);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("SCHEDULE_CONFLICT");
        List<Map<String, Object>> clashes = refused.at("$.details.clashes");
        assertThat(clashes).hasSize(1);
        assertThat(((Map<?, ?>) clashes.get(0).get("event")).get("title")).isEqualTo("Workshop A");
        assertThat(clashes.get(0)).containsEntry("status", "REGISTERED").containsEntry("canSwitch", true);
        assertThat(flows.statusOf(asha, b)).isNull();

        // The event page warns before the student even tries.
        assertThat((List<?>) api.as(asha).get("/api/events/" + b).at("$.data.viewer.clashes")).hasSize(1);

        // C starts exactly when A ends: no overlap.
        assertThat(register(asha, c).status()).isEqualTo(201);
    }

    @Test
    void switchingGivesUpEveryOverlappingPlaceTheSeatGoesToTheWaitlistAndRegisters() {
        ashaHoldsAAndC();
        // B overlaps both A and C: agreeing to give up only A isn't enough.
        Response partial = register(asha, b, "replace", List.of(a));
        assertThat(partial.status()).isEqualTo(409);
        assertThat((List<String>) partial.at("$.details.clashes[*].event.title")).containsExactly("Workshop A", "Quiz C");
        assertThat(flows.statusOf(asha, a)).isEqualTo("REGISTERED");

        Response switched = register(asha, b, "replace", List.of(a, c));
        assertThat(switched.status()).isEqualTo(201);
        assertThat((List<String>) switched.at("$.data.switchedFrom[*].title")).containsExactly("Workshop A", "Quiz C");
        assertThat(flows.statusOf(asha, b)).isEqualTo("REGISTERED");
        assertThat(List.of(flows.statusOf(asha, a), flows.statusOf(asha, c))).containsExactly("CANCELLED", "CANCELLED");
        // A's freed seat went to Bina, who was waiting.
        assertThat(flows.statusOf(bina, a)).isEqualTo("REGISTERED");
    }

    @Test
    void aWaitlistPlaceCountsToo() {
        register(dev, w);
        assertThat((Boolean) register(chirag, w).at("$.data.waitlisted")).isTrue();
        Response refused = register(chirag, x);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.string("$.details.clashes[0].status")).isEqualTo("WAITLISTED");
        assertThat(refused.string("$.details.clashes[0].event.title")).isEqualTo("Lab W");
        assertThat(register(chirag, x, "replace", List.of(w)).status()).isEqualTo(201);
        assertThat(flows.statusOf(chirag, w)).isEqualTo("CANCELLED");
    }

    @Test
    void teamsABusyLeaderCantStartATeamWithoutSwitchingABusyInviteeIsAskedWhenAccepting() {
        register(bina, i);
        long teamsBefore = mongo.count(new Query(), Team.class);
        Response refused = register(bina, t, "teamName", "Bina's team");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("SCHEDULE_CONFLICT");
        assertThat(mongo.count(new Query(), Team.class)).isEqualTo(teamsBefore);

        register(dev, i);
        Response created = register(chirag, t, "teamName", "Byte Busters", "invitees", List.of(dev.id()));
        assertThat(created.status()).isEqualTo(201);
        // The leader sees who's busy when inviting.
        List<Map<String, Object>> devRow = api.as(chirag).get("/api/events/" + t + "/team/candidates?search=Dev").at("$.data[?(@.name == 'Dev')]");
        assertThat(devRow.get(0)).containsEntry("busyWith", "Seminar I");
        Response binaRow = api.as(chirag).get("/api/events/" + t + "/team/candidates?search=Bina");
        assertThat(binaRow.string("$.data[0].name")).isEqualTo("Bina");
        assertThat(binaRow.string("$.data[0].busyWith")).isEqualTo("Seminar I");

        String teamId = created.string("$.data.team._id");
        Response accept = api.as(dev).post("/api/events/" + t + "/teams/" + teamId + "/accept");
        assertThat(accept.status()).isEqualTo(409);
        assertThat(accept.string("$.details.clashes[0].event.title")).isEqualTo("Seminar I");
        Response joined = api.as(dev).post("/api/events/" + t + "/teams/" + teamId + "/accept", map("replace", List.of(i)));
        assertThat(joined.status()).isEqualTo(200);
        assertThat(joined.string("$.data.switchedFrom[0].title")).isEqualTo("Seminar I");
        assertThat(flows.statusOf(dev, i)).isEqualTo("CANCELLED");
        assertThat(flows.statusOf(dev, t)).isEqualTo("REGISTERED");
    }

    @Test
    void youCantSwitchAwayFromAnEventThatHasAlreadyStarted() {
        ashaSwitchedToB();
        // B is now under way (started an hour ago, still running until day 10).
        flows.set(b, new Update().set("startAt", Instant.now().minus(Duration.ofHours(1))));
        String z = publish(venues.auditorium(), "title", "Meetup Z", "eventDate", TestData.futureDate(5), "startTime", "10:00", "endTime", "11:00",
                "registrationEnd", EventFlows.fromNow(Duration.ofDays(4)));
        Response refused = register(asha, z);
        assertThat(refused.status()).isEqualTo(409);
        assertThat((Boolean) refused.at("$.details.clashes[0].canSwitch")).isFalse();
        assertThat(refused.string("$.details.clashes[0].event.title")).isEqualTo("Talk B");
        Response stillRefused = register(asha, z, "replace", List.of(b));
        assertThat(stillRefused.status()).isEqualTo(409);
        assertThat(stillRefused.message()).contains("already started");
        assertThat(flows.statusOf(asha, b)).isEqualTo("REGISTERED");
    }
}
