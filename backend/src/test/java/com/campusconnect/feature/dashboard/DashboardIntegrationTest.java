package com.campusconnect.feature.dashboard;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.feature.event.EventFlows;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * GET /api/dashboard: the dashboard assertions of the original registrationResults, waitlist, attendance, eventEdits
 * and auth tests, plus the faculty and admin review queues and the notification counts.
 */
class DashboardIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser member;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty();
        president = data.student(spec -> spec.name("President"));
        member = data.student(spec -> spec.name("Member"));
        club = data.activeClub("AI Club", mentor, president);
        data.addMembership(club, member);
    }

    String publishEvent(Object... overrides) {
        return flows.published(president, mentor, club, venues.auditorium(), overrides);
    }

    Response dashboard(TestUser user) {
        Response res = api.as(user).get("/api/dashboard");
        assertThat(res.status()).as(res.body()).isEqualTo(200);
        return res;
    }

    @Test
    void signedOutAndForgedTokensAreRejected() {
        assertThat(api.get("/api/dashboard").status()).isEqualTo(401);
        assertThat(api.withToken("not-a-real-token").get("/api/dashboard").status()).isEqualTo(401);
    }

    @Test
    void eachRoleReceivesItsOwnSection() {
        Response student = dashboard(president);
        assertThat(student.message()).isEqualTo("Dashboard fetched");
        assertThat(student.string("$.data.role")).isEqualTo("STUDENT");
        assertThat(student.string("$.data.student.clubWorkspaces[0].club.name")).isEqualTo("AI Club");
        assertThat(student.string("$.data.student.clubWorkspaces[0].roleName")).isEqualTo("President");
        assertThat(student.has("$.data.campusEvents")).isFalse();
        assertThat(student.has("$.data.faculty")).isFalse();

        Response faculty = dashboard(mentor);
        assertThat(faculty.string("$.data.role")).isEqualTo("FACULTY");
        assertThat(faculty.string("$.data.faculty.mentoredClubs[0].name")).isEqualTo("AI Club");
        assertThat(faculty.string("$.data.faculty.mentoredClubs[0].president.name")).isEqualTo("President");
        assertThat((Integer) faculty.at("$.data.faculty.mentoredClubs[0].memberCount")).isEqualTo(2);
        assertThat(faculty.has("$.data.campusEvents")).isTrue();
        assertThat(faculty.has("$.data.student")).isFalse();

        Response admin = dashboard(data.admin());
        assertThat(admin.string("$.data.role")).isEqualTo("ADMIN");
        assertThat(admin.has("$.data.admin.stats")).isTrue();
        assertThat(admin.string("$.data.admin.recentClubs[0].name")).isEqualTo("AI Club");
        assertThat(admin.string("$.data.admin.recentClubs[0].mentor.name")).isEqualTo(mentor.name());
        assertThat(admin.has("$.data.campusEvents")).isTrue();
    }

    @Test
    void theStudentDashboardShowsEachEventOnce() {
        String attending = publishEvent("title", "Dashboard Attending", "eventDate", TestData.futureDate(30), "registrationEnd", EventFlows.fromNow(Duration.ofDays(28)));
        String open = publishEvent("title", "Dashboard Open", "eventDate", TestData.futureDate(31), "registrationEnd", EventFlows.fromNow(Duration.ofDays(29)));
        publishEvent("title", "Dashboard ME only", "eligibility", map("departments", List.of("ME"), "batches", List.of()), "eventDate", TestData.futureDate(32),
                "registrationEnd", EventFlows.fromNow(Duration.ofDays(30)));

        TestUser fan = data.student(spec -> spec.name("Fan"));
        assertThat(flows.register(fan, attending).status()).isEqualTo(201);

        Response fanView = dashboard(fan);
        List<String> upcoming = fanView.at("$.data.student.upcomingRegistrations[*].event.title");
        List<String> recommended = fanView.at("$.data.student.recommended[*].title");
        assertThat(upcoming).containsExactly("Dashboard Attending");
        assertThat(recommended).contains("Dashboard Open").doesNotContain("Dashboard Attending", "Dashboard ME only");
        assertThat((Integer) fanView.at("$.data.student.stats.upcoming")).isEqualTo(1);
        List<String> everything = new ArrayList<>(upcoming);
        everything.addAll(recommended);
        assertThat(new HashSet<>(everything)).hasSize(everything.size());
        assertThat(fanView.string("$.data.student.recommended[0].registrationState")).isEqualTo("OPEN");
        assertThat(fanView.string("$.data.student.recommended[0].club.name")).isEqualTo("AI Club");
        assertThat(fanView.string("$.data.student.recommended[0].venue.name")).isEqualTo("Auditorium");
        assertThat((Boolean) fanView.at("$.data.student.recommended[0].fromMyClub")).isFalse();

        // A plain member sees their club's events recommended first; the president manages them in the workspace instead.
        List<Boolean> fromMyClub = dashboard(member).at("$.data.student.recommended[?(@._id == '" + open + "')].fromMyClub");
        assertThat(fromMyClub).containsExactly(true);
        Response presidentView = dashboard(president);
        assertThat(presidentView.<List<String>>at("$.data.student.recommended[*].title")).doesNotContain("Dashboard Open");
        assertThat(presidentView.<List<String>>at("$.data.student.clubWorkspaces[0].upcoming[*].title")).contains("Dashboard Open");
        assertThat(presidentView.has("$.data.campusEvents")).isFalse();
    }

    @Test
    void managersSeeTheClubsNumbersOtherOfficersDoNot() {
        TestUser coordinator = data.student(spec -> spec.name("Coordinator"));
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        String rushHour = publishEvent("title", "Rush Hour", "maxParticipants", 3, "eventDate", TestData.futureDate(12));
        String calledOff = publishEvent("title", "Called Off", "eventDate", TestData.futureDate(14));
        flows.set(calledOff, new Update().set("status", "CANCELLED"));
        flows.draft(president, club, venues.hall(), "title", "Still Planning", "eventDate", TestData.futureDate(20));
        for (int i = 0; i < 5; i++) {
            flows.register(data.student(), rushHour);
        }

        Response res = dashboard(president);
        // The cancelled event doesn't count; the draft is in the pipeline.
        assertThat(res.<Map<String, Object>>at("$.data.student.clubWorkspaces[0].insights"))
                .containsEntry("totalMembers", 3)
                .containsEntry("totalEvents", 1)
                .containsEntry("eventsInPipeline", 1)
                .containsEntry("upcomingEvents", 1)
                .containsEntry("completedEvents", 0)
                .containsEntry("totalRegistrations", 3)
                .containsEntry("waitlisted", 2)
                .containsEntry("seatFillRate", 100)
                .containsEntry("attendanceRate", null);
        assertThat(res.<Number>at("$.data.student.clubWorkspaces[0].insights.averageParticipation").doubleValue()).isEqualTo(3.0);
        List<Map<String, Object>> eventWise = res.at("$.data.student.clubWorkspaces[0].insights.eventWise");
        assertThat(eventWise).extracting(row -> List.of(row.get("title"), row.get("registered"), row.get("capacity"), row.get("waitlist")))
                .containsExactly(List.of("Rush Hour", 3, 3, 2));

        Response other = dashboard(coordinator);
        assertThat(other.<Object>at("$.data.student.clubWorkspaces[0].insights")).isNull();
        assertThat(other.<Object>at("$.data.student.clubWorkspaces[0].recruitment")).isNull();
        assertThat(other.<List<String>>at("$.data.student.clubWorkspaces[0].drafts[*].title")).containsExactly("Still Planning");
        // A plain member runs nothing, so has no workspace.
        assertThat(dashboard(member).<List<Object>>at("$.data.student.clubWorkspaces")).isEmpty();
    }

    @Test
    void insightsCountAttendanceAtTheDoor() {
        String bootcamp = publishEvent("title", "Web Dev Bootcamp", "maxParticipants", 10, "eventDate", TestData.futureDate(12));
        List<TestUser> students = List.of(data.student(), data.student(), data.student(), data.student());
        students.forEach(student -> assertThat(flows.register(student, bootcamp).status()).isEqualTo(201));
        // The event took place and three of the four were checked in at the door.
        flows.times(bootcamp, Duration.ofDays(-10), Duration.ofDays(-3), Duration.ofDays(-2), Duration.ofDays(-2).plusHours(3));
        flows.set(bootcamp, new Update().set("status", "COMPLETED"));
        for (TestUser student : students.subList(0, 3)) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(flows.registrationOf(student, bootcamp).getId()))),
                    new Update().set("checkedInAt", Instant.now().minus(Duration.ofDays(2))), EventRegistration.class);
        }

        Response res = dashboard(president);
        assertThat(res.<Map<String, Object>>at("$.data.student.clubWorkspaces[0].insights"))
                .containsEntry("completedEvents", 1)
                .containsEntry("attendanceRate", 75)
                .containsEntry("totalAttended", 3);
        List<Map<String, Object>> rows = res.at("$.data.student.clubWorkspaces[0].insights.eventWise[?(@.title == 'Web Dev Bootcamp')]");
        assertThat(rows).singleElement().satisfies(row -> assertThat(row).containsEntry("registered", 4).containsEntry("attended", 3).containsEntry("upcoming", false));
        // Its results are still to be published.
        assertThat(res.<List<String>>at("$.data.student.clubWorkspaces[0].resultsPending[*].title")).containsExactly("Web Dev Bootcamp");
        assertThat(res.<List<Object>>at("$.data.student.clubWorkspaces[0].resultsPending[*].resultStatus")).containsExactly((Object) null);

        Response attendee = dashboard(students.get(0));
        assertThat(attendee.<List<String>>at("$.data.student.pastRegistrations[*].event.title")).containsExactly("Web Dev Bootcamp");
        assertThat((Boolean) attendee.at("$.data.student.pastRegistrations[0].hasResults")).isFalse();
        assertThat((Integer) attendee.at("$.data.student.stats.attended")).isEqualTo(1);
    }

    @Test
    void theMentorSeesTheEventsAndEditsWaitingForThem() {
        String live = publishEvent("title", "Hack Night", "eventDate", TestData.futureDate(20));
        Response edit = api.as(president).put("/api/events/" + live, map(
                "title", "Hack Night: Winter Edition",
                "venue", venues.hall().getId(),
                "eventDate", TestData.futureDate(21),
                "startTime", "16:00",
                "endTime", "20:00",
                "registrationEnd", EventFlows.fromNow(Duration.ofDays(19)),
                "updateNote", "We moved to a smaller hall with better Wi-Fi."));
        assertThat(edit.status()).as(edit.body()).isEqualTo(200);
        String submitted = flows.draft(president, club, venues.hall(), "title", "Robot Wars", "eventDate", TestData.futureDate(25));
        assertThat(api.as(president).post("/api/events/" + submitted + "/submit").status()).isEqualTo(200);

        Response faculty = dashboard(mentor);
        assertThat(faculty.<List<String>>at("$.data.faculty.eventsToReview[*].title")).containsExactlyInAnyOrder("Hack Night", "Robot Wars");
        assertThat(faculty.<List<String>>at("$.data.faculty.eventsToReview[?(@.title == 'Hack Night')].revision.status")).containsExactly("PENDING_APPROVAL");
        assertThat(faculty.<List<String>>at("$.data.faculty.eventsToReview[?(@.title == 'Hack Night')].club.name")).containsExactly("AI Club");
        assertThat(faculty.<List<String>>at("$.data.faculty.upcomingEvents[*].title")).containsExactly("Hack Night");
        // The campus events leave out the mentor's own clubs' events, already listed above.
        assertThat(faculty.<List<String>>at("$.data.campusEvents[*].title")).doesNotContain("Hack Night");

        Response workspace = dashboard(president);
        assertThat(workspace.<List<String>>at("$.data.student.clubWorkspaces[0].changesInReview[*].title")).containsExactly("Hack Night");
        assertThat(workspace.<List<String>>at("$.data.student.clubWorkspaces[0].pendingApproval[*].title")).containsExactly("Robot Wars");
        assertThat(workspace.<Object>at("$.data.student.clubWorkspaces[0].pendingApproval[0].revision")).isNull();
        assertThat(workspace.string("$.data.student.clubWorkspaces[0].pendingApproval[0].venue.name")).isEqualTo("Seminar Hall A");
        assertThat(workspace.string("$.data.student.clubWorkspaces[0].pendingApproval[0].club")).isEqualTo(club.getId());
    }

    @Test
    void clubProposalsReachTheRightQueues() {
        TestUser founder = data.student(spec -> spec.name("Founder"));
        TestUser otherFaculty = data.faculty("CE", "Other Faculty");
        Instant now = Instant.now();
        insertRequest("Robotics Club", "PENDING_FACULTY_REVIEW", founder, mentor, null, now.minusSeconds(300));
        insertRequest("Open Mentor Club", "PENDING_FACULTY_REVIEW", founder, null, null, now.minusSeconds(200));
        insertRequest("Someone Else's Club", "PENDING_FACULTY_REVIEW", founder, otherFaculty, null, now.minusSeconds(100));
        insertRequest("Verified Club", "FACULTY_VERIFIED", founder, mentor, mentor, now);
        Club chess = new Club();
        chess.setName("Chess Club");
        chess.setCategory("CULTURAL");
        chess.setMentor(mentor.id());
        chess.setStatus(ClubStatus.APPROVED);
        mongo.insert(chess);

        Response faculty = dashboard(mentor);
        assertThat(faculty.<List<String>>at("$.data.faculty.requestsToReview[*].name")).containsExactly("Robotics Club", "Open Mentor Club");
        assertThat(faculty.<Map<String, Object>>at("$.data.faculty.requestsToReview[0].requester"))
                .containsOnlyKeys("_id", "name", "email", "departmentCode")
                .containsEntry("name", "Founder");
        assertThat(faculty.<List<String>>at("$.data.faculty.verifiedRequests[*].name")).containsExactly("Verified Club");
        assertThat(faculty.<List<String>>at("$.data.faculty.clubsAwaitingPresident[*].name")).containsExactly("Chess Club");
        assertThat(faculty.<List<String>>at("$.data.faculty.mentoredClubs[*].name")).containsExactly("AI Club", "Chess Club");

        Response admin = dashboard(data.admin());
        assertThat(admin.<List<String>>at("$.data.admin.awaitingApproval[*].name")).containsExactly("Verified Club");
        assertThat(admin.<Map<String, Object>>at("$.data.admin.awaitingApproval[0].requester")).containsOnlyKeys("_id", "name", "email");
        assertThat(admin.string("$.data.admin.awaitingApproval[0].verifiedBy.name")).isEqualTo(mentor.name());

        // The founder follows their own proposals.
        assertThat(dashboard(founder).<List<String>>at("$.data.student.clubRequests[*].name")).hasSize(4);
    }

    @Test
    void theUnreadCountAndTheFiveLatestNotifications() {
        for (int i = 1; i <= 6; i++) {
            mongo.insert(new Document("user", new ObjectId(member.id()))
                    .append("type", "ANNOUNCEMENT")
                    .append("title", "Notice " + i)
                    .append("message", "Message " + i)
                    .append("readAt", i <= 2 ? new Date() : null)
                    .append("createdAt", Date.from(Instant.now().minusSeconds(60L * (10 - i)))), "notifications");
        }
        Response view = dashboard(member);
        assertThat((Integer) view.at("$.data.unreadNotifications")).isEqualTo(4);
        assertThat(view.<List<String>>at("$.data.student.recentNotifications[*].title")).containsExactly("Notice 6", "Notice 5", "Notice 4", "Notice 3", "Notice 2");
    }

    void insertRequest(String name, String status, TestUser requester, TestUser proposedMentor, TestUser verifiedBy, Instant at) {
        mongo.insert(new Document("name", name)
                .append("status", status)
                .append("requester", new ObjectId(requester.id()))
                .append("foundingMembers", List.of())
                .append("proposedMentor", proposedMentor == null ? null : new ObjectId(proposedMentor.id()))
                .append("verifiedBy", verifiedBy == null ? null : new ObjectId(verifiedBy.id()))
                .append("verifiedAt", verifiedBy == null ? null : Date.from(at))
                .append("createdAt", Date.from(at))
                .append("updatedAt", Date.from(at)), "clubcreationrequests");
    }
}
