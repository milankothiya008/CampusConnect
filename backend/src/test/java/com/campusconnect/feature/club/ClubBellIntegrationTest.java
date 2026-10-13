package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.tokens.UnsubscribeTokens;
import com.campusconnect.domain.club.Club;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * The club bell, unsubscribe and e-mail settings tests of tests/integration/notifications.test.js. The Node tests read
 * the unsubscribe tokens out of the e-mails sent when an event is published (the events feature); these create the same
 * tokens with {@link UnsubscribeTokens}.
 */
class ClubBellIntegrationTest extends IntegrationTest {

    @Autowired
    UnsubscribeTokens unsubscribeTokens;

    TestUser mentor;
    TestUser president;
    TestUser member;
    TestUser mutedMember;
    TestUser itFollower;
    TestUser ceStudent;
    Club club;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
        mentor = data.faculty("CE", "Mentor");
        president = data.student(s -> s.name("President"));
        member = data.student(s -> s.name("Member"));
        mutedMember = data.student(s -> s.name("Muted Member"));
        itFollower = data.student(s -> s.name("IT Follower").department("IT"));
        ceStudent = data.student(s -> s.name("CE Student"));
        TestUser noRecommendations = data.student(s -> s.name("No Recommendations"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, member, "MEMBER");
        data.addMembership(club, mutedMember, "MEMBER");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(noRecommendations.id())), new Update().set("emailPreferences.eventRecommendations", false), "users");
    }

    String subscription() {
        return "/api/clubs/" + club.getId() + "/subscription";
    }

    @Nested
    class TheBell {

        @Test
        void membersStartWithNotificationsOnOthersStartWithThemOff() {
            Response mine = api.as(member).get(subscription());
            assertThat((Boolean) mine.at("$.data.subscribed")).isTrue();
            assertThat((Boolean) mine.at("$.data.emailsEnabled")).isTrue();
            assertThat((Boolean) api.as(itFollower).get(subscription()).at("$.data.subscribed")).isFalse();
        }

        @Test
        void anyoneCanTurnItOnMembersCanTurnItOffAndTheClubShowsItsFollowerCount() {
            Response on = api.as(itFollower).put(subscription(), Map.of("enabled", true));
            assertThat((Boolean) on.at("$.data.subscribed")).isTrue();
            assertThat(on.message()).isEqualTo("Notifications turned on");

            Response off = api.as(mutedMember).put(subscription(), Map.of("enabled", false));
            assertThat((Boolean) off.at("$.data.subscribed")).isFalse();
            assertThat(off.message()).isEqualTo("Notifications turned off");

            Response detail = api.as(itFollower).get("/api/clubs/" + club.getId());
            assertThat((Boolean) detail.at("$.data.viewer.subscribed")).isTrue();
            // President + member (muted member opted out) + IT follower.
            assertThat((Integer) detail.at("$.data.followerCount")).isEqualTo(3);

            Response mine = api.as(itFollower).get("/api/notifications/subscriptions");
            assertThat((List<String>) mine.at("$.data[*].club.name")).containsExactly("Coding Club");
        }

        @Test
        void theBellCanOnlyBeTurnedOnForActiveClubsAndNeedsABoolean() {
            Club suspended = data.activeClub("Paused Club", mentor, data.student());
            mongo.updateFirst(Query.query(Criteria.where("_id").is(suspended.getId())), new Update().set("status", "SUSPENDED"), Club.class);
            String pausedUrl = "/api/clubs/" + suspended.getId() + "/subscription";
            assertThat(api.as(ceStudent).put(pausedUrl, Map.of("enabled", true)).status()).isEqualTo(409);
            assertThat(api.as(ceStudent).put(pausedUrl, Map.of("enabled", false)).status()).isEqualTo(200);
            Response notBoolean = api.as(ceStudent).put(subscription(), Map.of("enabled", "yes"));
            assertThat(notBoolean.status()).isEqualTo(400);
            assertThat(notBoolean.message()).isEqualTo("enabled must be true or false");
            assertThat(api.put(subscription(), Map.of("enabled", true)).status()).isEqualTo(401);
        }
    }

    @Nested
    class UnsubscribeLinks {

        String clubToken;
        String categoryToken;

        @BeforeEach
        void tokens() {
            api.as(itFollower).put(subscription(), Map.of("enabled", true));
            clubToken = unsubscribeTokens.create(itFollower.id(), "club", club.getId());
            categoryToken = unsubscribeTokens.create(ceStudent.id(), "pref", "eventRecommendations");
        }

        Response unsubscribe(Object body) {
            return api.post("/api/notifications/unsubscribe", body);
        }

        @Test
        void showWhatTheySwitchOffWithoutSigningIn() {
            Response res = api.get("/api/notifications/unsubscribe?token=" + URLEncoder.encode(clubToken, StandardCharsets.UTF_8));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.scope")).isEqualTo("club");
            assertThat(res.string("$.data.label")).isEqualTo("Emails from Coding Club");
            assertThat(res.string("$.data.email")).matches("^\\d{2}\\*\\*\\*@ddu\\.ac\\.in$");
        }

        @Test
        void oneClickUnsubscribeFromAMailAppTurnsTheClubsBellOff() {
            Response res = api.send(HttpMethod.POST, "/api/notifications/unsubscribe?token=" + URLEncoder.encode(clubToken, StandardCharsets.UTF_8), null,
                    request -> request.contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"));
            assertThat(res.status()).isEqualTo(200);
            assertThat((Boolean) api.as(itFollower).get(subscription()).at("$.data.subscribed")).isFalse();
        }

        @Test
        void aCategoryLinkSwitchesOffOnlyThatKindOfEmail() {
            Response res = unsubscribe(Map.of("token", categoryToken));
            assertThat(res.string("$.data.scope")).isEqualTo("pref");
            assertThat(res.string("$.data.label")).isEqualTo("New events for you");
            assertThat((Boolean) res.at("$.data.unsubscribed")).isTrue();
            Response prefs = api.as(ceStudent).get("/api/notifications/preferences");
            assertThat((Map<String, Object>) prefs.at("$.data.preferences"))
                    .isEqualTo(Map.of("clubUpdates", true, "eventRecommendations", false, "eventActivity", true, "recruitment", true));
        }

        @Test
        void tamperedOrMissingTokensAreRejected() {
            String[] parts = clubToken.split("\\.");
            String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"u\":\"" + member.id() + "\",\"s\":\"club\",\"k\":\"" + club.getId() + "\"}").getBytes(StandardCharsets.UTF_8));
            assertThat(unsubscribe(Map.of("token", parts[0] + ".wrongsignature")).status()).isEqualTo(400);
            assertThat(unsubscribe(Map.of("token", forgedPayload + "." + parts[1])).status()).isEqualTo(400);
            assertThat(unsubscribe(Map.of()).status()).isEqualTo(400);
        }
    }

    @Nested
    class EmailSettings {

        @Test
        void listTheOptionalCategoriesAndSaveChanges() {
            Response res = api.as(member).get("/api/notifications/preferences");
            assertThat((List<String>) res.at("$.data.categories[*].key")).containsExactly("clubUpdates", "eventRecommendations", "eventActivity", "recruitment");

            Response saved = api.as(member).put("/api/notifications/preferences", Map.of("clubUpdates", false));
            assertThat((Boolean) saved.at("$.data.preferences.clubUpdates")).isFalse();
            assertThat((Boolean) api.as(member).get(subscription()).at("$.data.emailsEnabled")).isFalse();

            assertThat(api.as(member).put("/api/notifications/preferences", Map.of("clubUpdates", "nope")).status()).isEqualTo(400);
            assertThat(api.as(member).put("/api/notifications/preferences", Map.of()).status()).isEqualTo(400);
            api.as(member).put("/api/notifications/preferences", Map.of("clubUpdates", true));
        }
    }
}
