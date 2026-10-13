package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Port of tests/integration/schedule.test.js ("event planner schedule"). */
class EventPlannerIntegrationTest extends IntegrationTest {

    @Autowired
    PausedClubs pausedClubs;

    final String day = TestData.futureDate(12);
    TestData.Venues venues;
    EventFlows flows;
    TestUser admin;
    TestUser mentor;
    TestUser codingPresident;
    TestUser dramaPresident;
    TestUser member;
    TestUser outsider;
    Club coding;
    Club drama;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        admin = data.admin();
        mentor = data.faculty("CE", "Mentor");
        codingPresident = data.student(spec -> spec.name("Coding President"));
        dramaPresident = data.student(spec -> spec.name("Drama President"));
        member = data.student(spec -> spec.name("Plain Member"));
        outsider = data.student(spec -> spec.name("Outsider"));
        coding = data.activeClub("Coding Club", mentor, codingPresident);
        drama = data.activeClub("Drama Club", mentor, dramaPresident);
        data.addMembership(coding, member, "MEMBER");

        makeEvent(codingPresident, coding, venues.auditorium(), "PUBLISHED", "title", "Hack Night", "startTime", "18:00", "endTime", "22:00",
                "eligibility", map("departments", List.of("CE", "IT")));
        makeEvent(dramaPresident, drama, venues.hall(), "PENDING_APPROVAL", "title", "Open Mic", "startTime", "19:00", "endTime", "21:00");
        makeEvent(dramaPresident, drama, venues.hall(), "DRAFT", "title", "Secret Draft", "startTime", "10:00", "endTime", "11:00");
        makeEvent(codingPresident, coding, venues.auditorium(), "CANCELLED", "title", "Called Off", "startTime", "10:00", "endTime", "11:00");
    }

    void makeEvent(TestUser president, Club club, Venue venue, String status, Object... overrides) {
        Object[] withDay = new Object[overrides.length + 2];
        withDay[0] = "eventDate";
        withDay[1] = day;
        System.arraycopy(overrides, 0, withDay, 2, overrides.length);
        String id = flows.draft(president, club, venue, withDay);
        flows.set(id, new Update().set("status", status));
    }

    Response schedule(TestUser viewer, String from, int days) {
        return api.as(viewer).get("/api/events/schedule?from=" + from + "&days=" + days);
    }

    Response schedule(TestUser viewer) {
        return schedule(viewer, day, 1);
    }

    @Test
    void showsLiveAndApprovedEventsAndEventsAwaitingApprovalAsTentativeNeverDraftsOrCancelledOnes() {
        Response res = schedule(dramaPresident);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.string("$.data.fromDate")).isEqualTo(day);
        assertThat((List<String>) res.at("$.data.items[*].title")).containsExactly("Hack Night", "Open Mic");
        Map<String, Object> hack = res.at("$.data.items[0]");
        Map<String, Object> mic = res.at("$.data.items[1]");
        assertThat(hack).containsEntry("tentative", false).containsEntry("audience", List.of("CE", "IT")).containsEntry("mine", false);
        assertThat(((Map<?, ?>) hack.get("club")).get("name")).isEqualTo("Coding Club");
        assertThat(mic).containsEntry("tentative", true).containsEntry("status", "PENDING_APPROVAL").containsEntry("mine", true);
        assertThat(Duration.between(Instant.parse((String) hack.get("startAt")), Instant.parse((String) hack.get("endAt")))).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void theWindowFollowsTheUniversityCalendarDay() {
        assertThat((List<?>) schedule(admin, TestData.futureDate(13), 1).at("$.data.items")).isEmpty();
        assertThat((List<?>) schedule(admin, TestData.futureDate(9), 7).at("$.data.items")).hasSize(2);
    }

    @Test
    void officersFacultyAndTheAdminCanPlanPlainMembersAndOtherStudentsCannot() {
        for (TestUser viewer : List.of(admin, mentor, codingPresident)) {
            assertThat(schedule(viewer).status()).as(viewer.name()).isEqualTo(200);
        }
        assertThat(schedule(member).status()).isEqualTo(403);
        assertThat(schedule(outsider).status()).isEqualTo(403);
        Response tooLong = api.as(admin).get("/api/events/schedule?days=90");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.message()).isEqualTo("days must be 1 to 31");
    }

    @Test
    void pausedClubsEventsFreeTheirSlots() {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(drama.getId()))), new Update().set("status", "SUSPENDED"), Club.class);
        pausedClubs.evict();
        Response res = schedule(admin);
        assertThat((List<String>) res.at("$.data.items[*].title")).containsExactly("Hack Night");
    }
}
