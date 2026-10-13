package com.campusconnect.feature.feed;

import static com.campusconnect.feature.feed.SocialFixtures.assumeRoute;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubSubscription;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.User;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.time.Instant;
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
 * Port of tests/integration/social.test.js ("profiles, the activity badge and following from the feed"). Profiles,
 * club pages and the events feed belong to the users, clubs and events features: those tests are skipped while their
 * routes are not part of the build and run once all features are assembled.
 */
class SocialIntegrationTest extends IntegrationTest {

    TestUser mentor;
    TestUser president;
    TestUser member;
    TestUser stranger;
    Club club;
    Club otherClub;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
        mentor = data.faculty("CE", "Dr Mentor");
        president = data.student(s -> s.name("Club President"));
        member = data.student(s -> s.name("Asha Member"));
        stranger = data.student(s -> s.name("Stranger"));
        club = data.activeClub("Coding Club", mentor, president);
        otherClub = data.activeClub("Drama Club", mentor, stranger);
        data.addMembership(club, member, "MEMBER");
    }

    Response profile(TestUser viewer, String userId) {
        return (viewer == null ? api : api.as(viewer)).get("/api/users/" + userId + "/profile");
    }

    @Nested
    class SomeoneElsesProfile {

        @Test
        void showsNameRoleDepartmentBatchClubsAndHistoryNeverEmailOrUpcomingSchedule() {
            TestUser loner = data.student(s -> s.name("Not In A Club"));
            Response res = assumeRoute(profile(loner, member.id()));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.name")).isEqualTo("Asha Member");
            assertThat(res.string("$.data.departmentCode")).isEqualTo("CE");
            assertThat(res.string("$.data.batchCode")).isEqualTo("24");
            assertThat((Boolean) res.at("$.data.isSelf")).isFalse();
            assertThat((Map<String, Object>) res.at("$.data.history")).isEqualTo(Map.of("events", List.of(), "awards", List.of(), "certificates", List.of()));
            List<String> clubNames = res.at("$.data.clubs[*].club.name");
            List<String> roleNames = res.at("$.data.clubs[*].roleName");
            assertThat(clubNames).containsExactly("Coding Club");
            assertThat(roleNames).containsExactly("Member");
            assertThat(res.body()).doesNotContain(member.email());
            // Students outside every club don't see mobile numbers; club members (the club directory rule) do.
            assertThat((Object) res.at("$.data.phone")).isNull();
            assertThat(profile(stranger, member.id()).string("$.data.phone")).startsWith("+91");
            assertThat(res.has("$.data.registrations")).isFalse();
        }

        @Test
        void aFacultyMembersNumberIsShownToClubMembersFacultyAndTheAdminNotOtherStudents() {
            TestUser loner = data.student(s -> s.name("Another Loner"));
            assertThat((Object) assumeRoute(profile(loner, mentor.id())).at("$.data.phone")).isNull();
            String facultyPhone = "+919811112222";
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(mentor.id()))), new Update().set("phone", facultyPhone), User.class);
            assertThat(profile(member, mentor.id()).string("$.data.phone")).isEqualTo(facultyPhone);

            Response memberView = assumeRoute(api.as(member).get("/api/clubs/" + club.getId()));
            assertThat(memberView.string("$.data.mentor.phone")).isEqualTo(facultyPhone);
            assertThat(api.as(loner).get("/api/clubs/" + club.getId()).has("$.data.mentor.phone")).isFalse();
            List<Map<String, Object>> clubs = api.as(loner).get("/api/clubs").at("$.data");
            assertThat(clubs).allMatch(item -> !(item.get("mentor") instanceof Map<?, ?> mentorView) || mentorView.get("phone") == null || "".equals(mentorView.get("phone")));
        }

        @Test
        void aFacultyProfileListsTheClubsTheyMentorYourOwnProfileIsMarkedAsYours() {
            Response faculty = assumeRoute(profile(member, mentor.id()));
            List<String> mentored = faculty.at("$.data.mentoredClubs[*].name");
            assertThat(mentored).containsExactly("Coding Club", "Drama Club");
            assertThat((Boolean) profile(member, member.id()).at("$.data.isSelf")).isTrue();
        }

        @Test
        void signedInUsersOnly() {
            assertThat(assumeRoute(profile(null, member.id())).status()).isEqualTo(401);
            assertThat(profile(member, "64b000000000000000000000").status()).isEqualTo(404);
        }
    }

    @Test
    void theUnreadCountIsSplitIntoEventsClubsAndRecruitmentForTheActivityBubble() {
        mongo.insertAll(List.of(
                notification(NotificationType.EVENT_PUBLISHED, "New event", null),
                notification(NotificationType.REGISTRATION_CONFIRMED, "You're in", null),
                notification(NotificationType.ANNOUNCEMENT, "News", null),
                notification(NotificationType.RECRUITMENT_OFFER, "Offer", null),
                notification(NotificationType.EVENT_UPDATED, "Seen", Instant.now())));
        Response res = api.as(member).get("/api/notifications/unread-count");
        assertThat((Map<String, Object>) res.at("$.data")).isEqualTo(Map.of("count", 4, "kinds", Map.of("events", 2, "clubs", 1, "recruitment", 1)));

        api.as(member).post("/api/notifications/read-all");
        assertThat((Integer) api.as(member).get("/api/notifications/unread-count").at("$.data.count")).isZero();
    }

    @Test
    void feedEventsSayWhetherTheViewerFollowsTheClub() {
        SocialFixtures.publishedEvent(mongo, club, "Hack Night");
        SocialFixtures.publishedEvent(mongo, otherClub, "Open Mic");
        assumeRoute(api.as(member).get("/api/events?timeframe=upcoming"));

        // Members follow their club unless they switch it off; others follow by choice.
        assertThat(following(member)).isEqualTo(Map.of("Hack Night", true, "Open Mic", false));
        subscribe(member, otherClub, true);
        subscribe(member, club, false);
        assertThat(following(member)).isEqualTo(Map.of("Hack Night", false, "Open Mic", true));
    }

    Map<String, Object> following(TestUser viewer) {
        List<Map<String, Object>> events = api.as(viewer).get("/api/events?timeframe=upcoming").at("$.data");
        Map<String, Object> result = new LinkedHashMap<>();
        events.forEach(event -> result.put((String) event.get("title"), event.get("followingClub")));
        return result;
    }

    void subscribe(TestUser user, Club target, boolean enabled) {
        ClubSubscription subscription = new ClubSubscription();
        subscription.setUser(user.id());
        subscription.setClub(target.getId());
        subscription.setEnabled(enabled);
        mongo.insert(subscription);
    }

    Notification notification(NotificationType type, String title, Instant readAt) {
        Notification notification = new Notification();
        notification.setUser(member.id());
        notification.setType(type);
        notification.setTitle(title);
        notification.setReadAt(readAt);
        return notification;
    }
}
