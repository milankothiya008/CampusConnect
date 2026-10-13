package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/integration/clubProfile.test.js; tests that read an earlier test's profile save it first. */
class ClubProfileIntegrationTest extends IntegrationTest {

    @Nested
    class WebsiteSocialLinksContactAndMeetings {

        Club club;
        TestUser president;
        TestUser mentor;
        TestUser admin;
        TestUser member;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            president = data.student();
            mentor = data.faculty();
            admin = data.admin();
            member = data.student();
            club = data.activeClub("Robotics Club", mentor, president);
            data.addMembership(club, member, "MEMBER");
        }

        Response update(TestUser user, Map<String, Object> body) {
            return api.as(user).put("/api/clubs/" + club.getId(), body);
        }

        Response saveFullProfile() {
            Map<String, Object> social = new LinkedHashMap<>();
            social.put("instagram", "https://www.instagram.com/ddu.robotics");
            social.put("linkedin", "linkedin.com/company/ddu-robotics");
            social.put("github", "https://github.com/ddu-robotics");
            social.put("whatsapp", "https://chat.whatsapp.com/AbCdEf123");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tagline", "Build robots, break things, learn fast");
            body.put("website", "roboticsclub.ddu.ac.in");
            body.put("contactPhone", "+91 98765 43210");
            body.put("meetingSchedule", "Every Friday, 4:00–5:30 PM");
            body.put("meetingLocation", "Lab 204, CE Block");
            body.put("coverImage", "/uploads/club-covers/robotics.jpg");
            body.put("socialLinks", social);
            return update(president, body);
        }

        @Test
        void thePresidentSavesAFullPublicProfileAndAnyoneCanReadIt() {
            assertThat(saveFullProfile().status()).isEqualTo(200);

            Response view = api.get("/api/clubs/" + club.getId());
            assertThat(view.string("$.data.tagline")).isEqualTo("Build robots, break things, learn fast");
            assertThat(view.string("$.data.website")).isEqualTo("https://roboticsclub.ddu.ac.in/");
            assertThat(view.string("$.data.contactPhone")).isEqualTo("+91 98765 43210");
            assertThat(view.string("$.data.meetingSchedule")).isEqualTo("Every Friday, 4:00–5:30 PM");
            assertThat(view.string("$.data.meetingLocation")).isEqualTo("Lab 204, CE Block");
            assertThat(view.string("$.data.coverImage")).isEqualTo("/uploads/club-covers/robotics.jpg");
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("instagram", "https://www.instagram.com/ddu.robotics");
            expected.put("linkedin", "https://linkedin.com/company/ddu-robotics");
            expected.put("x", null);
            expected.put("youtube", null);
            expected.put("facebook", null);
            expected.put("github", "https://github.com/ddu-robotics");
            expected.put("discord", null);
            expected.put("whatsapp", "https://chat.whatsapp.com/AbCdEf123");
            assertThat((Map<String, Object>) view.at("$.data.socialLinks")).isEqualTo(expected);
        }

        @Test
        void clearingAFieldRemovesIt() {
            saveFullProfile();
            Response res = update(president, Map.of("website", "", "socialLinks", Map.of("instagram", "https://instagram.com/ddu.robotics")));
            assertThat((Object) res.at("$.data.website")).isNull();
            assertThat((Object) res.at("$.data.socialLinks.github")).isNull();
            assertThat(res.string("$.data.socialLinks.instagram")).isEqualTo("https://instagram.com/ddu.robotics");
        }

        @Test
        void rejectsUnsafeOrMismatchedLinksWithAClearMessage() {
            List<Object[]> cases = List.of(
                    new Object[] {Map.of("website", "javascript:alert(1)"), "Website must be a valid web address"},
                    new Object[] {Map.of("website", "not a url"), "Website must be a valid web address"},
                    new Object[] {Map.of("website", "https://user:secret@example.com"), "Website must not contain a username or password"},
                    new Object[] {Map.of("socialLinks", Map.of("instagram", "https://evil.example.com/instagram.com")), "Instagram link must be on instagram.com"},
                    new Object[] {Map.of("socialLinks", Map.of("youtube", "https://notyoutube.com/watch")), "YouTube link must be on youtube.com"},
                    new Object[] {Map.of("socialLinks", Map.of("myspace", "https://myspace.com/club")), "Unsupported social link: myspace"},
                    new Object[] {Map.of("contactPhone", "call me"), "Enter a valid phone number, e.g. +91 98765 43210"});
            for (Object[] testCase : cases) {
                @SuppressWarnings("unchecked")
                Response res = update(president, (Map<String, Object>) testCase[0]);
                assertThat(res.status()).as(testCase[1].toString()).isEqualTo(400);
                assertThat(res.message()).isEqualTo(testCase[1]);
            }
        }

        @Test
        void onlyTheClubsLeadershipCanEditTheProfile() {
            saveFullProfile();
            for (TestUser user : List.of(member, mentor, admin)) {
                assertThat(update(user, Map.of("tagline", "Hijacked")).status()).isEqualTo(403);
            }
            assertThat(api.get("/api/clubs/" + club.getId()).string("$.data.tagline")).isEqualTo("Build robots, break things, learn fast");
        }

        @Test
        void enforcesLengthLimits() {
            assertThat(update(president, Map.of("tagline", "x".repeat(141))).status()).isEqualTo(400);
            assertThat(update(president, Map.of("meetingSchedule", "x".repeat(121))).status()).isEqualTo(400);
        }
    }
}
