package com.campusconnect.feature.clubrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The club-request routes beyond the ported lifecycle: validation, visibility, rejection, approval details. */
class ClubRequestRoutesIntegrationTest extends IntegrationTest {

    TestUser student;
    TestUser founder;
    TestUser faculty;
    TestUser otherFaculty;
    TestUser itFaculty;
    TestUser admin;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
        student = data.student(s -> s.name("Requester"));
        founder = data.student(s -> s.name("Founder"));
        faculty = data.faculty("CE", "Dr Mentor");
        otherFaculty = data.faculty("CE", "Dr Other");
        itFaculty = data.faculty("IT", "Dr IT");
        admin = data.admin();
    }

    static Map<String, Object> body(Object... overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Chess Club");
        body.put("description", "Weekly chess for every level of player.");
        body.put("purpose", "Grow a chess community on campus.");
        body.put("proposedActivities", "Weekly games, coaching and a spring tournament.");
        body.put("reason", "There is no place to play chess on campus.");
        body.put("departmentCodes", List.of("CE"));
        body.put("category", "OTHER");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return body;
    }

    String submit(Object... overrides) {
        Response res = api.as(student).post("/api/club-requests", body(overrides));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        return res.string("$.data._id");
    }

    String named() {
        return submit("foundingMemberEmails", List.of(founder.email()), "proposedMentor", faculty.id());
    }

    String verified() {
        String id = named();
        assertThat(api.as(faculty).post("/api/club-requests/" + id + "/verify", Map.of("comment", "Good plan.")).status()).isEqualTo(200);
        return id;
    }

    boolean notified(TestUser user, String title) {
        return mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("title").is(title)), "notifications");
    }

    @Nested
    class Submitting {

        @Test
        void reportsEveryBrokenRuleInOrder() {
            Response res = api.as(student).post("/api/club-requests", Map.of());
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.message()).isEqualTo("Invalid value. Club name must be 3-120 characters. Invalid value. Description must be 10-4000 characters. "
                    + "Invalid value. Purpose must be 10-2000 characters. Invalid value. Proposed activities must be 10-4000 characters. "
                    + "Invalid value. Reason must be 10-2000 characters. Invalid category");
            assertThat(api.as(student).post("/api/club-requests", body("allDepartments", "maybe")).message()).isEqualTo("allDepartments must be true or false");
            assertThat(api.as(student).post("/api/club-requests", body("foundingMemberEmails", List.of("not-an-email"))).message())
                    .isEqualTo("Founding members must be university emails");
            assertThat(api.as(student).post("/api/club-requests", body("proposedMentor", "abc")).message()).isEqualTo("Invalid proposed mentor");
            assertThat(api.as(student).post("/api/club-requests", body("departmentCodes", "CE")).message()).isEqualTo("departmentCodes must be a list");
            // The role check comes before the body is looked at.
            assertThat(api.as(faculty).post("/api/club-requests", Map.of()).status()).isEqualTo(403);
        }

        @Test
        void storesTheRequestWithItsFirstHistoryEntryAndTellsTheNamedMentor() {
            Response res = api.as(student).post("/api/club-requests", body("name", "  Chess Club  ", "allDepartments", "false",
                    "foundingMemberEmails", List.of(founder.email().toUpperCase(), student.email()), "proposedMentor", faculty.id()));
            assertThat(res.status()).isEqualTo(201);
            assertThat(res.message()).isEqualTo("Club request submitted for faculty review");
            assertThat(res.string("$.data.name")).isEqualTo("Chess Club");
            assertThat(res.string("$.data.requester.name")).isEqualTo("Requester");
            assertThat((List<String>) res.at("$.data.foundingMembers[*]._id")).containsExactly(founder.id());
            assertThat(res.string("$.data.proposedMentor.name")).isEqualTo("Dr Mentor");
            assertThat((Object) res.at("$.data.verifiedBy")).isNull();
            assertThat((Object) res.at("$.data.club")).isNull();
            assertThat(res.string("$.data.history[0].action")).isEqualTo("CLUB_REQUEST_SUBMITTED");
            assertThat((Object) res.at("$.data.history[0].fromStatus")).isNull();
            assertThat(res.string("$.data.history[0].actor.name")).isEqualTo("Requester");
            assertThat(notified(faculty, "New club request to review")).isTrue();
            assertThat(data.lastMailTo(faculty.email()).subject()).isEqualTo("New club request to review");
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("CLUB_REQUEST_SUBMITTED")), "auditlogs")).isTrue();
        }
    }

    @Nested
    class Reading {

        @Test
        void eachPartySeesTheRequestOutsidersDoNot() {
            String id = named();
            for (TestUser user : List.of(student, founder, faculty, admin)) {
                assertThat(api.as(user).get("/api/club-requests/" + id).status()).as(user.name()).isEqualTo(200);
            }
            assertThat(api.as(otherFaculty).get("/api/club-requests/" + id).message()).isEqualTo("You cannot view this request");
            assertThat(api.as(data.student()).get("/api/club-requests/" + id).status()).isEqualTo(403);
            assertThat(api.as(student).get("/api/club-requests/0123456789abcdef01234567").message()).isEqualTo("Club request not found");
            assertThat(api.as(student).get("/api/club-requests/nope").message()).isEqualTo("Invalid id");
        }

        @Test
        void listsFilterByStatusAndPaginate() {
            String id = named();
            Response mine = api.as(founder).get("/api/club-requests");
            assertThat(mine.message()).isEqualTo("Club requests fetched");
            assertThat((List<String>) mine.at("$.data[*]._id")).containsExactly(id);
            assertThat((Map<String, Object>) mine.at("$.meta")).isEqualTo(Map.of("total", 1, "page", 1, "limit", 20, "totalPages", 1));
            assertThat((List<?>) api.as(student).get("/api/club-requests?status=rejected").at("$.data")).isEmpty();
            assertThat((List<String>) api.as(admin).get("/api/club-requests?status=all").at("$.data[*]._id")).containsExactly(id);
            assertThat((List<?>) api.as(otherFaculty).get("/api/club-requests").at("$.data")).isEmpty();
        }
    }

    @Nested
    class Reviewing {

        @Test
        void anOpenRequestCanBeReviewedByFacultyOfItsDepartmentsOnly() {
            String id = submit();
            Response wrongDepartment = api.as(itFaculty).post("/api/club-requests/" + id + "/verify", Map.of());
            assertThat(wrongDepartment.status()).isEqualTo(403);
            assertThat(wrongDepartment.message()).isEqualTo("This club is for CE; only faculty from those departments can review it");

            Response verified = api.as(otherFaculty).post("/api/club-requests/" + id + "/verify", Map.of("comment", "  "));
            assertThat(verified.message()).isEqualTo("Club request verified and sent to the university admin");
            assertThat(verified.string("$.data.verifiedBy.name")).isEqualTo("Dr Other");
            assertThat((Object) verified.at("$.data.reviewComment")).isNull();
            assertThat(notified(student, "Club request verified by faculty")).isTrue();
            assertThat(notified(admin, "Club request awaiting approval")).isTrue();
            assertThat(api.as(otherFaculty).post("/api/club-requests/" + id + "/verify", Map.of()).message()).isEqualTo("Request is not awaiting faculty review");
        }

        @Test
        void requestedChangesNeedAReasonAndOnlyTheRequesterEditsAndResubmits() {
            String id = named();
            assertThat(api.as(faculty).post("/api/club-requests/" + id + "/request-changes", Map.of()).message())
                    .isEqualTo("Invalid value. Please describe the required changes (min 5 characters)");
            assertThat(api.as(faculty).post("/api/club-requests/" + id + "/request-changes", Map.of("comment", "abc")).message())
                    .isEqualTo("Please describe the required changes (min 5 characters)");
            assertThat(api.as(student).put("/api/club-requests/" + id, Map.of("reason", "Edited before review is asked.")).message())
                    .isEqualTo("Requests can only be edited when changes are requested");

            assertThat(api.as(faculty).post("/api/club-requests/" + id + "/request-changes", Map.of("comment", "Add a meeting room.")).status()).isEqualTo(200);
            assertThat(data.lastMailTo(student.email()).subject()).isEqualTo("Changes requested on your club request");
            assertThat(api.as(founder).post("/api/club-requests/" + id + "/resubmit").message()).isEqualTo("Only the requesting student can resubmit this request");
            assertThat(api.as(student).post("/api/club-requests/" + id + "/resubmit").status()).isEqualTo(200);
            assertThat(notified(faculty, "Club request resubmitted")).isTrue();
            assertThat(api.as(student).post("/api/club-requests/" + id + "/resubmit").message()).isEqualTo("Only requests with requested changes can be resubmitted");
        }

        @Test
        void theMentorRejectsARequestUnderReviewTheAdminAVerifiedOne() {
            String pending = named();
            assertThat(api.as(faculty).post("/api/club-requests/" + pending + "/reject", Map.of()).message())
                    .isEqualTo("Invalid value. A rejection reason is required (min 5 characters)");
            assertThat(api.as(student).post("/api/club-requests/" + pending + "/reject", Map.of("reason", "Not needed")).status()).isEqualTo(403);
            assertThat(api.as(admin).post("/api/club-requests/" + pending + "/reject", Map.of("reason", "Not needed")).message()).isEqualTo("Faculty access required");

            Response rejected = api.as(faculty).post("/api/club-requests/" + pending + "/reject", Map.of("reason", "  Overlaps with the Board Games Club  "));
            assertThat(rejected.message()).isEqualTo("Club request rejected");
            assertThat(rejected.string("$.data.status")).isEqualTo("REJECTED");
            assertThat(rejected.string("$.data.rejectionReason")).isEqualTo("Overlaps with the Board Games Club");
            assertThat(rejected.string("$.data.decidedBy.email")).isEqualTo(faculty.email());
            assertThat(data.lastMailTo(founder.email()).subject()).isEqualTo("Club request rejected");
            assertThat(api.as(faculty).post("/api/club-requests/" + pending + "/reject", Map.of("reason", "Again please")).message())
                    .isEqualTo("Request cannot be rejected in its current state");

            String verified = submit("name", "Debate Club", "proposedMentor", faculty.id());
            api.as(faculty).post("/api/club-requests/" + verified + "/verify", Map.of());
            assertThat(api.as(faculty).post("/api/club-requests/" + verified + "/reject", Map.of("reason", "Changed my mind")).message())
                    .isEqualTo("University admin access required");
            assertThat(api.as(admin).post("/api/club-requests/" + verified + "/reject", Map.of("reason", "Budget is not available")).string("$.data.status"))
                    .isEqualTo("REJECTED");
        }
    }

    @Nested
    class Approving {

        @Test
        void createsTheClubWithTheDefaultRolesAndTellsTheMentor() {
            String id = verified();
            Response res = api.as(admin).post("/api/club-requests/" + id + "/approve");
            assertThat(res.message()).isEqualTo("Club approved and created");
            assertThat(res.string("$.data.request.status")).isEqualTo("APPROVED");
            assertThat(res.string("$.data.request.club.status")).isEqualTo("APPROVED");
            assertThat(res.string("$.data.request.decidedBy.name")).isEqualTo("University Admin");
            assertThat(res.string("$.data.club.name")).isEqualTo("Chess Club");
            assertThat(res.string("$.data.club.mentor")).isEqualTo(faculty.id());
            assertThat(res.string("$.data.club.creationRequest")).isEqualTo(id);
            assertThat((List<String>) res.at("$.data.club.roles[*].key")).hasSize(7).contains("PRESIDENT", "MEMBER");
            assertThat(notified(faculty, "You are now a faculty mentor")).isTrue();
            assertThat(data.lastMailTo(student.email()).subject()).isEqualTo("Your club was approved!");
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("MENTOR_ASSIGNED").and("targetId").is(new ObjectId(res.string("$.data.club._id")))), "auditlogs")).isTrue();
            assertThat(api.as(admin).post("/api/club-requests/" + id + "/approve").message()).isEqualTo("Request is not awaiting admin approval");
        }

        @Test
        void aClubCreatedMeanwhileWithTheSameNameBlocksApproval() {
            String id = verified();
            data.activeClub("chess club", faculty, data.student());
            Response res = api.as(admin).post("/api/club-requests/" + id + "/approve");
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.message()).isEqualTo("A club with this name already exists");
            assertThat(api.as(faculty).post("/api/club-requests/" + id + "/approve").status()).isEqualTo(403);
        }
    }
}
