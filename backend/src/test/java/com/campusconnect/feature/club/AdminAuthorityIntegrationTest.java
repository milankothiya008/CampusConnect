package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Port of tests/integration/adminAuthority.test.js — the club-side tests. The event and feed tests belong to those
 * features, and the recruitment-drive assertion to the recruitment feature.
 */
class AdminAuthorityIntegrationTest extends IntegrationTest {

    @Nested
    class UniversityAdminAuthorityIsLimitedToApprovalStatusAndMentor {

        TestUser admin;
        TestUser mentor;
        TestUser otherFaculty;
        TestUser president;
        TestUser vicePresident;
        TestUser member;
        TestUser applicant;
        Club club;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            admin = data.admin();
            mentor = data.faculty("CE", "Mentor");
            otherFaculty = data.faculty("CE", "Other Faculty");
            president = data.student(s -> s.name("President"));
            vicePresident = data.student(s -> s.name("Vice President"));
            member = data.student(s -> s.name("Member"));
            applicant = data.student(s -> s.name("Applicant"));
            club = data.activeClub("Coding Club", mentor, president);
            data.addMembership(club, vicePresident, "VICE_PRESIDENT");
            data.addMembership(club, member, "MEMBER");
        }

        String clubUrl() {
            return "/api/clubs/" + club.getId();
        }

        @Test
        void clubDetailsOnlyMembersWithManageClubCanEditIncludingTheLogoAndName() {
            Map<String, Object> change = Map.of("description", "Changed by someone without authority.", "logo", "https://example.com/logo.png");
            for (TestUser user : List.of(admin, mentor, vicePresident, member)) {
                assertThat(api.as(user).put(clubUrl(), change).status()).isEqualTo(403);
            }
            Response res = api.as(president).put(clubUrl(), Map.of("name", "Coding & Robotics Club", "description", "Building software and robots together on campus.",
                    "logo", "https://example.com/logo.png"));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.name")).isEqualTo("Coding & Robotics Club");
            assertThat(res.string("$.data.logo")).isEqualTo("https://example.com/logo.png");
        }

        @Test
        void presidentOnlyTheFacultyMentorCanAppointANewOne() {
            Map<String, Object> body = Map.of("userId", vicePresident.id());
            assertThat(api.as(admin).post(clubUrl() + "/president", body).status()).isEqualTo(403);
            assertThat(api.as(otherFaculty).post(clubUrl() + "/president", body).status()).isEqualTo(403);
            assertThat(api.as(president).post(clubUrl() + "/president", body).status()).isEqualTo(403);
        }

        @Test
        void adminGetsNoClubPermissionsInTheClubView() {
            Response res = api.as(admin).get(clubUrl());
            assertThat(res.status()).isEqualTo(200);
            assertThat((List<?>) res.at("$.data.viewer.permissions")).isEmpty();
            assertThat((Boolean) res.at("$.data.viewer.isAdmin")).isTrue();
        }

        @Test
        void adminCanSeeTheMemberListButCannotManageMembersOrRoles() {
            assertThat(api.as(admin).get(clubUrl() + "/members").status()).isEqualTo(200);
            assertThat(api.as(admin).patch(clubUrl() + "/members/" + member.id() + "/role", Map.of("role", "TREASURER")).status()).isEqualTo(403);
            assertThat(api.as(admin).delete(clubUrl() + "/members/" + member.id()).status()).isEqualTo(403);
            assertThat(api.as(admin).post(clubUrl() + "/members", Map.of("userId", applicant.id())).status()).isEqualTo(403);
            assertThat(mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(applicant.id()))), "clubmemberships")).isFalse();
        }

        @Test
        void adminCanChangeClubStatusAndReassignTheFacultyMentor() {
            Response suspended = api.as(admin).post(clubUrl() + "/status", Map.of("status", "SUSPENDED", "reason", "Pending review"));
            assertThat(suspended.status()).isEqualTo(200);
            assertThat(suspended.string("$.data.status")).isEqualTo("SUSPENDED");
            assertThat(api.as(admin).post(clubUrl() + "/status", Map.of("status", "ACTIVE")).string("$.data.status")).isEqualTo("ACTIVE");

            assertThat(api.as(president).post(clubUrl() + "/status", Map.of("status", "ARCHIVED")).status()).isEqualTo(403);
            assertThat(api.as(mentor).put(clubUrl() + "/mentor", Map.of("mentorId", otherFaculty.id())).status()).isEqualTo(403);

            Response reassigned = api.as(admin).put(clubUrl() + "/mentor", Map.of("mentorId", otherFaculty.id()));
            assertThat(reassigned.status()).isEqualTo(200);
            assertThat(reassigned.string("$.data.mentor._id")).isEqualTo(otherFaculty.id());

            // The new mentor now holds the mentor's powers; the old one no longer does.
            assertThat(api.as(mentor).post(clubUrl() + "/president", Map.of("userId", vicePresident.id())).status()).isEqualTo(403);
            assertThat(api.as(otherFaculty).post(clubUrl() + "/president", Map.of("userId", vicePresident.id())).status()).isEqualTo(200);
        }
    }
}
