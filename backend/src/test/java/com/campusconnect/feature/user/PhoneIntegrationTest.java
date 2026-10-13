package com.campusconnect.feature.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.util.Phones;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.user.User;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/integration/phone.test.js (the offer-acceptance test belongs to the recruitment feature). */
class PhoneIntegrationTest extends IntegrationTest {

    @Nested
    class ClubMembersMobileNumbers {

        TestUser admin;
        TestUser mentor;
        TestUser otherMentor;
        TestUser idleFaculty;
        TestUser president;
        TestUser member;
        TestUser otherPresident;
        TestUser outsider;
        TestUser noPhone;
        Club codingClub;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            admin = data.admin();
            mentor = data.faculty("CE", "Coding Mentor");
            otherMentor = data.faculty("CE", "Drama Mentor");
            idleFaculty = data.faculty("CE", "Idle Faculty");
            president = data.student(s -> s.name("Coding President"));
            member = data.student(s -> s.name("Coding Member"));
            otherPresident = data.student(s -> s.name("Drama President"));
            outsider = data.student(s -> s.name("Outsider"));
            noPhone = data.student(s -> s.name("No Phone").phone(null));
            codingClub = data.activeClub("Coding Club", mentor, president);
            data.activeClub("Drama Club", otherMentor, otherPresident);
            data.addMembership(codingClub, member, "MEMBER");
        }

        @Test
        void numbersAreNormalisedToPlus91AndTenDigits() {
            assertThat(Phones.normalize("98765 43210")).isEqualTo("+919876543210");
            assertThat(Phones.normalize("+91-98765-43210")).isEqualTo("+919876543210");
            assertThat(Phones.normalize("098765 43210")).isEqualTo("+919876543210");
            assertThat(Phones.normalize("12345 67890")).isNull();
            assertThat(Phones.normalize("98765")).isNull();
        }

        @Test
        void aStudentSetsTheirOwnNumberAndSeesItOnTheirAccount() {
            Response saved = api.as(noPhone).put("/api/users/" + noPhone.id(), Map.of("phone", "+91 91234 56789"));
            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.string("$.data.phone")).isEqualTo("+919123456789");
            assertThat(api.as(noPhone).get("/api/auth/me").string("$.data.phone")).isEqualTo("+919123456789");

            assertThat(api.as(noPhone).put("/api/users/" + noPhone.id(), Map.of("phone", "555-0100")).status()).isEqualTo(400);
            assertThat(api.as(outsider).put("/api/users/" + noPhone.id(), Map.of("phone", "9876543210")).status()).isEqualTo(403);

            // Once on file it can be changed but never removed (it's required at sign-up for everyone).
            Response removed = api.as(noPhone).put("/api/users/" + noPhone.id(), Map.of("phone", ""));
            assertThat(removed.status()).isEqualTo(400);
            assertThat(removed.errorCode()).isEqualTo("PHONE_REQUIRED");
        }

        @Test
        void clubMembersCanChangeTheirNumberButNotRemoveIt() {
            Response res = api.as(member).put("/api/users/" + member.id(), Map.of("phone", ""));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.errorCode()).isEqualTo("PHONE_REQUIRED");
            assertThat(mongo.findById(member.id(), User.class).getPhone()).isNotBlank();
        }

        @Test
        void membersOfAnyClubEveryClubMentorAndTheAdminSeeAnyClubsMembersWithNumbers() {
            String url = "/api/clubs/" + codingClub.getId() + "/members";
            for (TestUser viewer : List.of(admin, mentor, otherMentor, president, member, otherPresident)) {
                Response res = api.as(viewer).get(url);
                assertThat(res.status()).as(viewer.name()).isEqualTo(200);
                List<String> phones = res.at("$.data[?(@.user._id == '" + member.id() + "')].user.phone");
                assertThat(phones).singleElement().asString().matches("^\\+91\\d{10}$");
            }
            assertThat(api.as(outsider).get(url).status()).isEqualTo(403);
            assertThat(api.as(idleFaculty).get(url).status()).isEqualTo(403);
        }

        @Test
        void theClubViewSaysWhetherTheViewerCanOpenTheMemberList() {
            String url = "/api/clubs/" + codingClub.getId();
            assertThat((Boolean) api.as(otherPresident).get(url).at("$.data.viewer.canSeeMembers")).isTrue();
            assertThat((Boolean) api.as(otherMentor).get(url).at("$.data.viewer.canSeeMembers")).isTrue();
            assertThat((Boolean) api.as(admin).get(url).at("$.data.viewer.canSeeMembers")).isTrue();
            assertThat((Boolean) api.as(outsider).get(url).at("$.data.viewer.canSeeMembers")).isFalse();
        }
    }
}
