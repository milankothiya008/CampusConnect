package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of tests/integration/clubLifecycle.test.js. The Node tests share state within a describe block; here every
 * test starts from an empty database and replays the earlier steps it depends on through the API. The two start-up
 * migration tests are not ported (the legacy migrations are not part of the Java backend).
 */
class ClubLifecycleIntegrationTest extends IntegrationTest {

    static Map<String, Object> requestBody(Object... overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Robotics Club");
        body.put("description", "Designing and building robots for campus competitions.");
        body.put("purpose", "Give students hands-on robotics experience.");
        body.put("proposedActivities", "Weekly build sessions, workshops and an annual robo-race.");
        body.put("reason", "There is no robotics community on campus yet.");
        body.put("departmentCodes", List.of("CE"));
        body.put("category", "TECHNOLOGY");
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                body.remove((String) overrides[i]);
            } else {
                body.put((String) overrides[i], overrides[i + 1]);
            }
        }
        return body;
    }

    boolean exists(String collection, Criteria criteria) {
        return mongo.exists(Query.query(criteria), collection);
    }

    @Nested
    class CreationReviewApprovalAndPresident {

        TestUser founder;
        TestUser cofounder;
        TestUser faculty;
        TestUser otherFaculty;
        TestUser admin;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            founder = data.student(s -> s.name("Founder"));
            cofounder = data.student(s -> s.name("Co-founder"));
            faculty = data.faculty("CE", "Dr Mentor");
            otherFaculty = data.faculty("CE", "Dr Other");
            admin = data.admin();
        }

        String submitted() {
            Response res = api.as(founder).post("/api/club-requests", requestBody("foundingMemberEmails", List.of(cofounder.email()), "proposedMentor", faculty.id()));
            assertThat(res.status()).as(res.body()).isEqualTo(201);
            return res.string("$.data._id");
        }

        String resubmittedAfterChanges() {
            String id = submitted();
            assertThat(api.as(faculty).post("/api/club-requests/" + id + "/request-changes", Map.of("comment", "Please add a safety plan for workshop tools.")).status()).isEqualTo(200);
            assertThat(api.as(founder).post("/api/club-requests/" + id + "/resubmit").status()).isEqualTo(200);
            return id;
        }

        String verified() {
            String id = resubmittedAfterChanges();
            assertThat(api.as(faculty).post("/api/club-requests/" + id + "/verify", Map.of("comment", "Looks great.")).status()).isEqualTo(200);
            return id;
        }

        String approvedClub() {
            Response res = api.as(admin).post("/api/club-requests/" + verified() + "/approve");
            assertThat(res.status()).as(res.body()).isEqualTo(200);
            return res.string("$.data.club._id");
        }

        String activeClub() {
            String clubId = approvedClub();
            assertThat(api.as(faculty).post("/api/clubs/" + clubId + "/president", Map.of("userId", founder.id())).status()).isEqualTo(200);
            return clubId;
        }

        @Test
        void onlyStudentsCanSubmitAClubRequestFoundingMembersAreResolvedByEmail() {
            assertThat(api.as(faculty).post("/api/club-requests", requestBody()).status()).isEqualTo(403);

            Response res = api.as(founder).post("/api/club-requests", requestBody("foundingMemberEmails", List.of(cofounder.email()), "proposedMentor", faculty.id()));
            assertThat(res.status()).isEqualTo(201);
            assertThat(res.string("$.data.status")).isEqualTo("PENDING_FACULTY_REVIEW");
            assertThat((List<String>) res.at("$.data.foundingMembers[*].email")).containsExactly(cofounder.email());
        }

        @Test
        void rejectsUnknownFoundingMembersAndDuplicateInFlightNames() {
            submitted();
            Response unknown = api.as(cofounder).post("/api/club-requests", requestBody("name", "Another Club", "foundingMemberEmails", List.of("24ce9999@ddu.ac.in")));
            assertThat(unknown.status()).isEqualTo(400);

            Response duplicate = api.as(cofounder).post("/api/club-requests", requestBody());
            assertThat(duplicate.status()).isEqualTo(409);
        }

        @Test
        void studentsCannotReviewAndAFacultyMemberWhoWasNotNamedCannotReview() {
            String id = submitted();
            assertThat(api.as(founder).post("/api/club-requests/" + id + "/verify").status()).isEqualTo(403);
            assertThat(api.as(otherFaculty).post("/api/club-requests/" + id + "/verify").status()).isEqualTo(403);
            assertThat(api.as(admin).post("/api/club-requests/" + id + "/approve").status()).isEqualTo(409);
        }

        @Test
        void facultyRequestsChangesTheStudentEditsAndResubmits() {
            String id = submitted();
            Response changes = api.as(faculty).post("/api/club-requests/" + id + "/request-changes", Map.of("comment", "Please add a safety plan for workshop tools."));
            assertThat(changes.status()).isEqualTo(200);
            assertThat(changes.string("$.data.status")).isEqualTo("NEEDS_CHANGES");

            assertThat(api.as(cofounder).put("/api/club-requests/" + id, Map.of("purpose", "Not my request to edit.")).status()).isEqualTo(403);

            Response edit = api.as(founder).put("/api/club-requests/" + id,
                    Map.of("proposedActivities", "Weekly build sessions with a supervised safety induction, workshops and a robo-race."));
            assertThat(edit.status()).isEqualTo(200);

            Response resubmit = api.as(founder).post("/api/club-requests/" + id + "/resubmit");
            assertThat(resubmit.string("$.data.status")).isEqualTo("PENDING_FACULTY_REVIEW");
            assertThat((List<String>) resubmit.at("$.data.history[*].toStatus")).containsExactly("PENDING_FACULTY_REVIEW", "NEEDS_CHANGES", "PENDING_FACULTY_REVIEW");
        }

        @Test
        void facultyVerifiesFacultyCannotGiveFinalApproval() {
            String id = resubmittedAfterChanges();
            Response verify = api.as(faculty).post("/api/club-requests/" + id + "/verify", Map.of("comment", "Looks great."));
            assertThat(verify.string("$.data.status")).isEqualTo("FACULTY_VERIFIED");
            assertThat(api.as(faculty).post("/api/club-requests/" + id + "/approve").status()).isEqualTo(403);
        }

        @Test
        void adminApprovesClubIsCreatedWithTheVerifyingFacultyAsMentorAndFoundersAsMembers() {
            String requestId = verified();
            Response res = api.as(admin).post("/api/club-requests/" + requestId + "/approve");
            assertThat(res.status()).isEqualTo(200);
            String clubId = res.string("$.data.club._id");

            Club club = mongo.findById(clubId, Club.class);
            assertThat(club.getStatus()).isEqualTo(ClubStatus.APPROVED);
            assertThat(club.getMentor()).isEqualTo(faculty.id());

            List<String> members = new ArrayList<>(mongo.find(Query.query(Criteria.where("club").is(clubId).and("status").is("APPROVED")), ClubMembership.class)
                    .stream().map(ClubMembership::getUser).toList());
            assertThat(members).containsExactlyInAnyOrder(founder.id(), cofounder.id());

            assertThat(exists("auditlogs", Criteria.where("action").is("CLUB_REQUEST_APPROVED").and("targetId").is(new ObjectId(requestId)))).isTrue();
            assertThat(exists("notifications", Criteria.where("user").is(new ObjectId(founder.id())).and("type").is("CLUB_REQUEST_UPDATE"))).isTrue();
        }

        @Test
        void anApprovedNotYetActiveClubIsHiddenFromThePublicDirectory() {
            String clubId = approvedClub();
            Response res = api.get("/api/clubs");
            assertThat((List<String>) res.at("$.data[*]._id")).doesNotContain(clubId);
            assertThat(api.get("/api/clubs/" + clubId).status()).isEqualTo(404);
        }

        @Test
        void onlyTheMentorCanAppointThePresidentAppointmentActivatesTheClub() {
            String clubId = approvedClub();
            assertThat(api.as(otherFaculty).post("/api/clubs/" + clubId + "/president", Map.of("userId", founder.id())).status()).isEqualTo(403);
            assertThat(api.as(cofounder).post("/api/clubs/" + clubId + "/president", Map.of("userId", cofounder.id())).status()).isEqualTo(403);

            Response res = api.as(faculty).post("/api/clubs/" + clubId + "/president", Map.of("userId", founder.id()));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("ACTIVE");
            assertThat(res.string("$.data.president._id")).isEqualTo(founder.id());

            ClubMembership membership = mongo.findOne(Query.query(Criteria.where("club").is(clubId).and("user").is(founder.id())), ClubMembership.class);
            assertThat(membership.getRole()).isEqualTo("PRESIDENT");
            // A new club is announced to everyone on campus by notification (not as a feed post).
            assertThat(exists("feedposts", Criteria.where("club").is(new ObjectId(clubId)))).isFalse();
            assertThat(exists("notifications", Criteria.where("user").is(new ObjectId(otherFaculty.id())).and("type").is("NEW_CLUB"))).isTrue();
        }

        @Test
        void theClubDetailReportsTheViewersClubRoleAndPermissions() {
            String clubId = activeClub();
            Response res = api.as(founder).get("/api/clubs/" + clubId);
            assertThat(res.string("$.data.viewer.role")).isEqualTo("PRESIDENT");
            assertThat((List<String>) res.at("$.data.viewer.permissions")).contains("ASSIGN_ROLES");
            assertThat(res.string("$.data.mentor.name")).isEqualTo("Dr Mentor");
            assertThat(((Number) res.at("$.data.memberCount")).intValue()).isEqualTo(2);
        }
    }

    @Nested
    class MembershipAndRoles {

        TestUser mentor;
        TestUser president;
        TestUser member;
        TestUser applicant;
        TestUser outsider;
        Club club;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            mentor = data.faculty();
            president = data.student(s -> s.name("President"));
            member = data.student(s -> s.name("Member"));
            applicant = data.student(s -> s.name("Applicant"));
            outsider = data.student(s -> s.name("Outsider"));
            club = data.activeClub(mentor, president);
            data.addMembership(club, member);
        }

        String url(String path) {
            return "/api/clubs/" + club.getId() + path;
        }

        @Test
        void joinRequestsAreGoneStudentsJoinThroughRecruitmentDrives() {
            assertThat(api.as(applicant).post(url("/join"), Map.of("message", "I love robots")).status()).isEqualTo(404);
            assertThat(api.as(president).get(url("/membership-requests")).status()).isEqualTo(404);
        }

        @Test
        void thePresidentAddsAStudentDirectlyPlainMembersAndOutsidersCannot() {
            assertThat(api.as(member).post(url("/members"), Map.of("userId", applicant.id())).status()).isEqualTo(403);
            assertThat(api.as(outsider).post(url("/members"), Map.of("userId", applicant.id())).status()).isEqualTo(403);

            Response res = api.as(president).post(url("/members"), Map.of("userId", applicant.id()));
            assertThat(res.status()).isEqualTo(201);
            assertThat(res.string("$.data.status")).isEqualTo("APPROVED");
            assertThat(api.as(president).post(url("/members"), Map.of("userId", applicant.id())).status()).isEqualTo(409);
            assertThat(exists("notifications", Criteria.where("user").is(new ObjectId(applicant.id())).and("type").is("MEMBERSHIP_APPROVED"))).isTrue();
        }

        @Test
        void onlyThePresidentAssignsRolesTheNewRoleGrantsItsPermissions() {
            data.addMembership(club, applicant);
            assertThat(api.as(member).patch(url("/members/" + applicant.id() + "/role"), Map.of("role", "EVENT_COORDINATOR")).status()).isEqualTo(403);
            assertThat(api.as(president).patch(url("/members/" + applicant.id() + "/role"), Map.of("role", "PRESIDENT")).status()).isEqualTo(400);

            Response res = api.as(president).patch(url("/members/" + applicant.id() + "/role"), Map.of("role", "EVENT_COORDINATOR"));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.role")).isEqualTo("EVENT_COORDINATOR");

            Response detail = api.as(applicant).get(url(""));
            assertThat((List<String>) detail.at("$.data.viewer.permissions")).contains("MANAGE_EVENTS", "MANAGE_PARTICIPANTS").doesNotContain("MANAGE_MEMBERS");
        }

        @Test
        void memberListIsRestrictedToMembersTheMentorAndAdmins() {
            assertThat(api.as(outsider).get(url("/members")).status()).isEqualTo(403);
            assertThat(api.as(member).get(url("/members")).status()).isEqualTo(200);
            Response mentorView = api.as(mentor).get(url("/members"));
            assertThat(mentorView.string("$.data[0].role")).isEqualTo("PRESIDENT");
        }

        @Test
        void thePresidentCannotBeRemovedAndCannotLeaveMembersCanLeave() {
            assertThat(api.as(president).delete(url("/members/" + president.id())).status()).isEqualTo(400);
            assertThat(api.as(president).post(url("/leave")).status()).isEqualTo(409);
            assertThat(api.as(member).post(url("/leave")).status()).isEqualTo(200);
        }

        @Test
        void thePresidentRemovesAMemberAuditEntriesAreRecorded() {
            data.addMembership(club, applicant);
            Response res = api.as(president).delete(url("/members/" + applicant.id()));
            assertThat(res.status()).isEqualTo(200);
            assertThat(exists("clubmemberships", Criteria.where("club").is(new ObjectId(club.getId())).and("user").is(new ObjectId(applicant.id())))).isFalse();
            assertThat(exists("auditlogs", Criteria.where("action").is("MEMBER_REMOVED"))).isTrue();
        }
    }

    @Nested
    class ClubDepartmentsDecideWhoCanMentor {

        TestUser student;
        TestUser ceFaculty;
        TestUser itFaculty;
        TestUser ecFaculty;
        TestUser admin;
        int counter;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            student = data.student();
            ceFaculty = data.faculty("CE", null);
            itFaculty = data.faculty("IT", null);
            ecFaculty = data.faculty("EC", null);
            admin = data.admin();
        }

        Response submit(Object... overrides) {
            Object[] all = new Object[overrides.length + 2];
            all[0] = "name";
            all[1] = "Scoped Club " + (++counter);
            System.arraycopy(overrides, 0, all, 2, overrides.length);
            return api.as(student).post("/api/club-requests", requestBody(all));
        }

        @Test
        void aClubNeedsAtLeastOneKnownDepartmentUnlessItIsOpenToAll() {
            assertThat(submit("departmentCodes", List.of()).status()).isEqualTo(400);
            assertThat(submit("departmentCodes", List.of("ZZ")).status()).isEqualTo(400);

            Response all = submit("departmentCodes", null, "allDepartments", true);
            assertThat(all.status()).isEqualTo(201);
            assertThat((Boolean) all.at("$.data.allDepartments")).isTrue();
            assertThat((List<?>) all.at("$.data.departmentCodes")).isEmpty();
        }

        @Test
        void aSingleDepartmentClubOnlyAcceptsAMentorFromThatDepartment() {
            Response wrong = submit("proposedMentor", itFaculty.id());
            assertThat(wrong.status()).isEqualTo(400);
            assertThat(wrong.message()).contains("needs a faculty mentor from that department");

            assertThat(submit("proposedMentor", ceFaculty.id()).status()).isEqualTo(201);
        }

        @Test
        void aMultiDepartmentClubAcceptsAMentorFromAnyOfItsDepartments() {
            assertThat(submit("departmentCodes", List.of("CE", "IT"), "proposedMentor", itFaculty.id()).status()).isEqualTo(201);
            assertThat(submit("departmentCodes", List.of("CE", "IT"), "proposedMentor", ecFaculty.id()).status()).isEqualTo(400);
        }

        @Test
        void anAllDepartmentsClubAcceptsAnyFacultyMentor() {
            assertThat(submit("allDepartments", true, "proposedMentor", ecFaculty.id()).status()).isEqualTo(201);
        }

        @Test
        void openRequestsAreOnlyOfferedToFacultyFromTheClubsDepartments() {
            Response open = submit("departmentCodes", List.of("CE", "IT"));
            String id = open.string("$.data._id");

            assertThat((List<String>) api.as(itFaculty).get("/api/club-requests").at("$.data[*]._id")).contains(id);
            assertThat((List<String>) api.as(ecFaculty).get("/api/club-requests").at("$.data[*]._id")).doesNotContain(id);
            assertThat(api.as(ecFaculty).get("/api/club-requests/" + id).status()).isEqualTo(403);
            assertThat(api.as(ecFaculty).post("/api/club-requests/" + id + "/verify").status()).isEqualTo(403);

            assertThat(api.as(itFaculty).post("/api/club-requests/" + id + "/verify").status()).isEqualTo(200);
            Response approved = api.as(admin).post("/api/club-requests/" + id + "/approve");
            assertThat(approved.status()).isEqualTo(200);

            Club club = mongo.findOne(Query.query(Criteria.where("name").is(open.string("$.data.name"))), Club.class);
            assertThat(club.getDepartmentCodes()).containsExactly("CE", "IT");
            assertThat(club.getMentor()).isEqualTo(itFaculty.id());

            // The admin can only reassign the mentor to faculty from the club's departments.
            assertThat(api.as(admin).put("/api/clubs/" + club.getId() + "/mentor", Map.of("mentorId", ecFaculty.id())).status()).isEqualTo(400);
            assertThat(api.as(admin).put("/api/clubs/" + club.getId() + "/mentor", Map.of("mentorId", ceFaculty.id())).status()).isEqualTo(200);
        }

        @Test
        void editingDepartmentsCannotLeaveTheNamedMentorOrTheRequesterOutsideTheClubsDepartments() {
            String id = submit("departmentCodes", List.of("CE", "IT"), "proposedMentor", itFaculty.id()).string("$.data._id");
            api.as(itFaculty).post("/api/club-requests/" + id + "/request-changes", Map.of("comment", "Please add more detail."));

            Response withoutMentor = api.as(student).put("/api/club-requests/" + id, Map.of("departmentCodes", List.of("CE")));
            assertThat(withoutMentor.status()).isEqualTo(400);
            assertThat(withoutMentor.message()).contains("needs a faculty mentor");

            Response withoutRequester = api.as(student).put("/api/club-requests/" + id, Map.of("departmentCodes", List.of("IT")));
            assertThat(withoutRequester.status()).isEqualTo(403);
            assertThat(withoutRequester.message()).contains("You are in CE");

            Response widened = api.as(student).put("/api/club-requests/" + id, Map.of("departmentCodes", List.of("CE", "IT", "ME")));
            assertThat(widened.status()).isEqualTo(200);
            assertThat((List<String>) widened.at("$.data.departmentCodes")).containsExactly("CE", "IT", "ME");
        }

        @Test
        void theMentorSearchOffersOnlyFacultyFromTheGivenDepartmentsNeverTheAdmin() {
            List<String> all = api.as(student).get("/api/users/search?q=ddu.ac.in&accountType=FACULTY").at("$.data[*].email");
            assertThat(all).contains(ceFaculty.email(), itFaculty.email(), ecFaculty.email()).doesNotContain(admin.email());
            List<String> scoped = api.as(student).get("/api/users/search?q=ddu.ac.in&accountType=FACULTY&departments=CE,IT").at("$.data[*].email");
            assertThat(scoped).contains(ceFaculty.email(), itFaculty.email()).doesNotContain(ecFaculty.email());
        }

        @Test
        void theDepartmentFilterOnTheClubDirectoryIncludesAllDepartmentsClubs() {
            TestUser president = data.student();
            Club onlyIt = data.activeClub("Only IT", itFaculty, president);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(onlyIt.getId())), new Update().set("departmentCodes", List.of("IT")), Club.class);
            Club everyone = data.activeClub("Everyone", ecFaculty, president);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(everyone.getId())), new Update().set("allDepartments", true).set("departmentCodes", List.of()), Club.class);

            List<String> names = api.as(student).get("/api/clubs?department=CE").at("$.data[*].name");
            assertThat(names).contains("Everyone").doesNotContain("Only IT");
        }
    }

    @Nested
    class StudentsOnlyBelongToClubsOfTheirOwnDepartment {

        TestUser ceStudent;
        TestUser itStudent;
        TestUser ecStudent;
        TestUser mentor;
        TestUser president;
        Club ceClub;
        Club openClub;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            ceStudent = data.student(s -> s.department("CE"));
            itStudent = data.student(s -> s.department("IT"));
            ecStudent = data.student(s -> s.department("EC"));
            mentor = data.faculty("CE", null);
            president = data.student(s -> s.department("CE").name("President"));
            ceClub = data.activeClub("CE Only", mentor, president);
            openClub = data.activeClub("Open Club", mentor, president);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(openClub.getId())), new Update().set("allDepartments", true).set("departmentCodes", List.of()), Club.class);
        }

        @Test
        void thePresidentAddsStudentsFromTheClubsDepartmentsAllDepartmentsClubsTakeAnyone() {
            assertThat(api.as(president).post("/api/clubs/" + ceClub.getId() + "/members", Map.of("userId", ceStudent.id())).status()).isEqualTo(201);
            assertThat(api.as(president).post("/api/clubs/" + openClub.getId() + "/members", Map.of("userId", itStudent.id())).status()).isEqualTo(201);
        }

        @Test
        void thePresidentCannotAddAStudentFromAnotherDepartment() {
            Response added = api.as(president).post("/api/clubs/" + ceClub.getId() + "/members", Map.of("userId", ecStudent.id()));
            assertThat(added.status()).isEqualTo(403);
            assertThat(added.message()).containsPattern("Not eligible: .*\\(EC\\)");
        }

        @Test
        void theMentorCanOnlyAppointAPresidentFromTheClubsDepartments() {
            assertThat(api.as(mentor).post("/api/clubs/" + ceClub.getId() + "/president", Map.of("userId", itStudent.id())).status()).isEqualTo(403);
        }

        @Test
        void theRequesterAndFoundingMembersOfAClubRequestMustBeFromItsDepartments() {
            Response notMine = api.as(ceStudent).post("/api/club-requests", requestBody("name", "Founders 1", "departmentCodes", List.of("IT")));
            assertThat(notMine.status()).isEqualTo(403);
            assertThat(notMine.message()).contains("You are in CE");

            Response outsider = api.as(ceStudent).post("/api/club-requests", requestBody("name", "Founders 2", "foundingMemberEmails", List.of(itStudent.email())));
            assertThat(outsider.status()).isEqualTo(403);
            assertThat(outsider.message()).containsPattern("Not eligible: .*\\(IT\\)");

            assertThat(api.as(ceStudent).post("/api/club-requests", requestBody("name", "Founders 3", "departmentCodes", List.of("CE", "IT"),
                    "foundingMemberEmails", List.of(itStudent.email()))).status()).isEqualTo(201);
            assertThat(api.as(ceStudent).post("/api/club-requests", requestBody("name", "Founders 4", "departmentCodes", null, "allDepartments", true,
                    "foundingMemberEmails", List.of(ecStudent.email()))).status()).isEqualTo(201);
        }
    }
}
