package com.campusconnect.feature.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubSubscription;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Club announcements and the feed: the announcement tests of tests/integration/notifications.test.js, the audience
 * tests of calendarAudience.test.js, the feed tests of registrationResults.test.js ("club announcements") and
 * adminAuthority.test.js, and the cases none of them cover.
 */
class FeedIntegrationTest extends IntegrationTest {

    @Autowired
    PausedClubs pausedClubs;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
    }

    Response postAs(TestUser user, Map<String, Object> body) {
        return api.as(user).post("/api/feed", body);
    }

    static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    List<String> titles(TestUser user, String query) {
        Response response = api.as(user).get("/api/feed" + query);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.at("$.data[*].title");
    }

    List<String> notified(String titlePart) {
        Query query = Query.query(Criteria.where("type").is("ANNOUNCEMENT").and("title").regex(java.util.regex.Pattern.quote(titlePart)));
        return mongo.findDistinct(query, "user", "notifications", ObjectId.class).stream().map(ObjectId::toHexString).toList();
    }

    boolean got(TestUser user, String title) {
        return mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("type").is("ANNOUNCEMENT").and("title").is(title)), Notification.class);
    }

    void follow(TestUser user, Club club, boolean enabled) {
        ClubSubscription subscription = new ClubSubscription();
        subscription.setUser(user.id());
        subscription.setClub(club.getId());
        subscription.setEnabled(enabled);
        mongo.insert(subscription);
    }

    void pause(Club club) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(club.getId()))), new Update().set("status", "SUSPENDED"), Club.class);
        pausedClubs.evict();
    }

    /** registrationResults.test.js "club announcements" (AI Club with officers and a reader outside the club). */
    @Nested
    class ClubAnnouncements {

        TestUser mentor;
        TestUser president;
        TestUser marketing;
        TestUser member;
        TestUser outsider;
        Club club;

        @BeforeEach
        void club() {
            mentor = data.faculty("CE", "Mentor");
            data.faculty("CE", "Other Faculty");
            president = data.student(s -> s.name("President"));
            TestUser coordinator = data.student(s -> s.name("Event Coordinator"));
            TestUser treasurer = data.student(s -> s.name("Treasurer"));
            marketing = data.student(s -> s.name("Marketing"));
            member = data.student(s -> s.name("Member"));
            outsider = data.student(s -> s.name("Feed Reader"));
            club = data.activeClub("AI Club", mentor, president);
            data.addMembership(club, coordinator, "EVENT_COORDINATOR");
            data.addMembership(club, treasurer, "TREASURER");
            data.addMembership(club, marketing, "MARKETING_COORDINATOR");
            data.addMembership(club, member, "MEMBER");
        }

        Response recruitmentDrive(TestUser author) {
            return postAs(author, body("club", club.getId(), "type", "ANNOUNCEMENT", "title", "Recruitment drive", "body", "We're hiring designers!"));
        }

        @Test
        void marketingCoordinatorsPostAnnouncementsPlainMembersCannot() {
            Response refused = recruitmentDrive(member);
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.message()).isEqualTo("You cannot post on behalf of this club");
            assertThat(recruitmentDrive(outsider).status()).isEqualTo(403);
            Response posted = recruitmentDrive(marketing);
            assertThat(posted.status()).isEqualTo(201);
            assertThat(posted.message()).isEqualTo("Post published");
        }

        @Test
        void publicAnnouncementsNotifyEveryone() {
            assertThat(recruitmentDrive(marketing).status()).isEqualTo(201);
            assertThat(notified("Recruitment drive")).contains(outsider.id(), member.id(), mentor.id()).doesNotContain(marketing.id());
        }

        @Test
        void membersOnlyAnnouncementsNotifyMembersAndTheMentorOnlyAndStayHiddenFromOthers() {
            assertThat(postAs(president, body("club", club.getId(), "type", "CLUB_UPDATE", "title", "Members meeting on Friday", "visibility", "MEMBERS")).status()).isEqualTo(201);

            assertThat(titles(member, "?club=" + club.getId() + "&limit=50")).contains("Members meeting on Friday");
            assertThat(titles(outsider, "?club=" + club.getId() + "&limit=50")).doesNotContain("Members meeting on Friday");

            assertThat(notified("Members meeting")).contains(member.id(), mentor.id()).doesNotContain(outsider.id());
        }

        @Test
        void aClubsAnnouncementsCanBeListedByClubAndTypeNoEventOrResultPostsAreCreated() {
            recruitmentDrive(marketing);
            postAs(president, body("club", club.getId(), "type", "CLUB_UPDATE", "title", "Office hours"));
            Response response = api.as(outsider).get("/api/feed?club=" + club.getId() + "&types=ANNOUNCEMENT,CLUB_UPDATE");
            List<String> found = response.at("$.data[*].title");
            List<String> types = response.at("$.data[*].type");
            assertThat(found).contains("Recruitment drive");
            assertThat(types).allMatch(type -> List.of("ANNOUNCEMENT", "CLUB_UPDATE").contains(type));
            assertThat(mongo.exists(Query.query(Criteria.where("type").in("EVENT", "RESULT")), FeedPost.class)).isFalse();
        }

        @Test
        void announcementsCanBeDeletedByTheirAuthorOrClubManagersOnly() {
            String id = recruitmentDrive(marketing).string("$.data._id");
            Response refused = api.as(member).delete("/api/feed/" + id);
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.message()).isEqualTo("You cannot delete this post");
            Response deleted = api.as(president).delete("/api/feed/" + id);
            assertThat(deleted.status()).isEqualTo(200);
            assertThat(deleted.message()).isEqualTo("Post deleted");

            String own = recruitmentDrive(marketing).string("$.data._id");
            assertThat(api.as(marketing).delete("/api/feed/" + own).status()).isEqualTo(200);
            assertThat(api.as(marketing).delete("/api/feed/" + own).status()).isEqualTo(404);
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("FEED_POST_DELETED").and("targetId").is(new ObjectId(own))), "auditlogs")).isTrue();
        }

        @Test
        void theFeedRequiresAuthentication() {
            assertThat(api.get("/api/feed").status()).isEqualTo(401);
        }
    }

    /** calendarAudience.test.js "announcements go only to the audience the club picks". */
    @Nested
    class AnnouncementsGoOnlyToTheAudienceTheClubPicks {

        TestUser president;
        TestUser treasurer;
        TestUser member;
        TestUser follower;
        TestUser ceStudent;
        TestUser itStudent;
        TestUser mentor;
        TestUser faculty;
        Club club;

        @BeforeEach
        void club() {
            president = data.student(s -> s.name("Prez"));
            treasurer = data.student(s -> s.name("Tara"));
            member = data.student(s -> s.name("Mona"));
            follower = data.student(s -> s.name("Fola"));
            ceStudent = data.student(s -> s.name("Cee"));
            itStudent = data.student(s -> s.name("Ita").department("IT"));
            mentor = data.faculty("CE", "Dr Mentor");
            faculty = data.faculty("CE", "Dr Other");
            club = data.activeClub("Coding Club", mentor, president);
            data.addMembership(club, treasurer, "TREASURER");
            data.addMembership(club, member);
            follow(follower, club, true);
        }

        Response post(Map<String, Object> extra) {
            Map<String, Object> body = body("club", club.getId(), "title", extra.get("title"), "body", "Details");
            body.putAll(extra);
            return postAs(president, body);
        }

        boolean got(TestUser user, String title) {
            return FeedIntegrationTest.this.got(user, "Coding Club: " + title);
        }

        @Test
        void chosenRolesDepartmentsAndPeopleNobodyElseIsNotifiedOrCanSeeIt() {
            Map<String, Object> roleAudience = body("mode", "CUSTOM", "roles", List.of("TREASURER"), "includeMentor", true);
            Response preview = api.as(president).post("/api/feed/audience-preview", body("club", club.getId(), "audience", roleAudience));
            assertThat(preview.status()).isEqualTo(200);
            assertThat(preview.message()).isEqualTo("OK");
            assertThat((Integer) preview.at("$.data.count")).isEqualTo(2);
            assertThat(preview.string("$.data.label")).isEqualTo("Treasurer · Faculty mentor");

            Response res = post(body("title", "Budget meeting", "audience", roleAudience));
            assertThat(res.status()).isEqualTo(201);
            assertThat(got(treasurer, "Budget meeting")).isTrue();
            assertThat(got(mentor, "Budget meeting")).isTrue();
            for (TestUser user : List.of(member, follower, ceStudent, faculty)) {
                assertThat(got(user, "Budget meeting")).as(user.name()).isFalse();
            }

            // Only the audience (and the club's posting team) can see the post on the club page.
            assertThat(titles(treasurer, "?club=" + club.getId())).contains("Budget meeting");
            assertThat(titles(president, "?club=" + club.getId())).contains("Budget meeting");
            assertThat(titles(member, "?club=" + club.getId())).doesNotContain("Budget meeting");
            assertThat(titles(ceStudent, "?club=" + club.getId())).doesNotContain("Budget meeting");

            post(body("title", "IT workshop", "audience", body("mode", "CUSTOM", "departments", List.of("IT"))));
            assertThat(got(itStudent, "IT workshop")).isTrue();
            assertThat(got(ceStudent, "IT workshop")).isFalse();

            post(body("title", "Hi Cee", "audience", body("mode", "CUSTOM", "users", List.of(ceStudent.id()))));
            assertThat(got(ceStudent, "Hi Cee")).isTrue();
            assertThat(got(member, "Hi Cee")).isFalse();

            Response nobody = post(body("title", "Nobody", "audience", body("mode", "CUSTOM")));
            assertThat(nobody.status()).isEqualTo(400);
            assertThat(nobody.message()).isEqualTo("Choose who should get this announcement");
            Response badRole = post(body("title", "Bad role", "audience", body("mode", "CUSTOM", "roles", List.of("NOPE"))));
            assertThat(badRole.status()).isEqualTo(400);
            assertThat(badRole.message()).isEqualTo("One of the chosen roles doesn't exist in this club");
        }

        @Test
        void followersMembersAndEveryone() {
            post(body("title", "For followers", "audience", body("mode", "FOLLOWERS")));
            assertThat(got(follower, "For followers")).isTrue();
            assertThat(got(member, "For followers")).isTrue();
            assertThat(got(ceStudent, "For followers")).isFalse();

            post(body("title", "Members only", "audience", body("mode", "MEMBERS")));
            assertThat(got(member, "Members only")).isTrue();
            assertThat(got(mentor, "Members only")).isTrue();
            assertThat(got(follower, "Members only")).isFalse();

            // The old visibility field still works.
            post(body("title", "Old style", "visibility", "MEMBERS"));
            assertThat(got(member, "Old style")).isTrue();
            assertThat(got(follower, "Old style")).isFalse();

            post(body("title", "Big news", "audience", body("mode", "EVERYONE")));
            for (TestUser user : List.of(member, follower, ceStudent, faculty)) {
                assertThat(got(user, "Big news")).as(user.name()).isTrue();
            }
            assertThat(got(president, "Big news")).isFalse();
        }
    }

    /** notifications.test.js "club bell, campaign emails and unsubscribe" — the announcements. */
    @Nested
    class AnnouncementEmails {

        TestUser president;
        TestUser member;
        TestUser mutedMember;
        TestUser itFollower;
        TestUser ceStudent;
        Club club;

        @BeforeEach
        void club() {
            TestUser mentor = data.faculty("CE", "Mentor");
            president = data.student(s -> s.name("President"));
            member = data.student(s -> s.name("Member"));
            mutedMember = data.student(s -> s.name("Muted Member"));
            itFollower = data.student(s -> s.name("IT Follower").department("IT"));
            ceStudent = data.student(s -> s.name("CE Student"));
            data.student(s -> s.name("EC Student").department("EC"));
            data.faculty("IT", "Other Faculty");
            club = data.activeClub("Coding Club", mentor, president);
            data.addMembership(club, member, "MEMBER");
            data.addMembership(club, mutedMember, "MEMBER");
            // Earlier in the original file: the IT follower turned the bell on, the muted member turned it off.
            follow(itFollower, club, true);
            follow(mutedMember, club, false);
        }

        @Test
        void aPublicAnnouncementIsEmailedToEveryoneWhoseBellIsOn() {
            Response res = postAs(president, body("club", club.getId(), "type", "ANNOUNCEMENT", "title", "Hackathon teams open",
                    "body", "Form teams of up to four.\n\nRegistration closes Friday."));
            assertThat(res.status()).isEqualTo(201);

            for (TestUser user : List.of(member, itFollower)) {
                List<DevInbox.Entry> mails = data.mailsTo(user.email());
                assertThat(mails).as(user.name()).hasSize(1);
                assertThat(mails.get(0).subject()).isEqualTo("Coding Club: Hackathon teams open");
                assertThat(mails.get(0).text()).contains("Form teams of up to four.\n\nRegistration closes Friday.");
            }
            for (TestUser user : List.of(mutedMember, ceStudent, president)) {
                assertThat(data.mailsTo(user.email())).as(user.name()).isEmpty();
            }
        }

        @Test
        void aMembersOnlyAnnouncementReachesOnlyMembersWithTheBellOn() {
            postAs(president, body("club", club.getId(), "type", "CLUB_UPDATE", "title", "Core team meeting", "body", "Monday 5 PM.", "visibility", "MEMBERS"));
            assertThat(data.mailsTo(member.email()).stream().map(DevInbox.Entry::subject).toList()).containsExactly("Coding Club: Core team meeting");
            assertThat(data.mailsTo(itFollower.email())).isEmpty();
            assertThat(data.mailsTo(mutedMember.email())).isEmpty();
        }

        @Test
        void switchingOffClubsYouFollowStopsClubEmailsEvenWithTheBellOn() {
            assertThat(api.as(itFollower).put("/api/notifications/preferences", Map.of("clubUpdates", false)).status()).isEqualTo(200);
            postAs(president, body("club", club.getId(), "type", "ANNOUNCEMENT", "title", "Another update", "body", "Details."));
            assertThat(data.mailsTo(itFollower.email())).isEmpty();
            assertThat(data.mailsTo(member.email())).hasSize(1);
            api.as(itFollower).put("/api/notifications/preferences", Map.of("clubUpdates", true));
        }
    }

    /** adminAuthority.test.js: the university admin has no authority inside a club. */
    @Nested
    class AdminAuthority {

        @Test
        void adminCannotPostForAClubDeleteClubPostsOrReadMembersOnlyPosts() {
            TestUser admin = data.admin();
            TestUser president = data.student(s -> s.name("President"));
            Club club = data.activeClub("Coding Club", data.faculty("CE", "Mentor"), president);

            assertThat(postAs(admin, body("club", club.getId(), "title", "Admin announcement")).status()).isEqualTo(403);

            Response post = postAs(president, body("club", club.getId(), "title", "Members meeting", "visibility", "MEMBERS"));
            assertThat(post.status()).isEqualTo(201);
            assertThat(titles(admin, "?club=" + club.getId() + "&limit=50")).doesNotContain("Members meeting");
            assertThat(api.as(admin).delete("/api/feed/" + post.string("$.data._id")).status()).isEqualTo(403);
        }
    }

    @Nested
    class BeyondTheOriginalTests {

        TestUser mentor;
        TestUser president;
        TestUser treasurer;
        TestUser marketing;
        TestUser member;
        TestUser outsider;
        Club club;

        @BeforeEach
        void club() {
            mentor = data.faculty("CE", "Dr Mentor");
            president = data.student(s -> s.name("Prez"));
            treasurer = data.student(s -> s.name("Tara"));
            marketing = data.student(s -> s.name("Mark"));
            member = data.student(s -> s.name("Mona"));
            outsider = data.student(s -> s.name("Otto"));
            club = data.activeClub("Coding Club", mentor, president);
            data.addMembership(club, treasurer, "TREASURER");
            data.addMembership(club, marketing, "MARKETING_COORDINATOR");
            data.addMembership(club, member);
        }

        @Test
        void anAudiencePostIsInvisibleToPeopleOutsideItsAudience() {
            Response posted = postAs(president, body("club", club.getId(), "title", "Treasury handover",
                    "audience", body("mode", "CUSTOM", "roles", List.of("TREASURER"))));
            assertThat(posted.status()).isEqualTo(201);
            assertThat(posted.string("$.data.visibility")).isEqualTo("AUDIENCE");
            assertThat(posted.string("$.data.audience.label")).isEqualTo("Treasurer");
            assertThat((Integer) posted.at("$.data.recipientCount")).isEqualTo(1);
            assertThat(posted.has("$.data.recipients")).isFalse();

            for (TestUser insider : List.of(treasurer, president, marketing, mentor)) {
                assertThat(titles(insider, "")).as(insider.name()).contains("Treasury handover");
            }
            TestUser admin = data.admin();
            for (TestUser other : List.of(member, outsider, admin)) {
                assertThat(titles(other, "")).as(other.name()).doesNotContain("Treasury handover");
                assertThat(titles(other, "?club=" + club.getId())).as(other.name()).doesNotContain("Treasury handover");
            }
            Response listed = api.as(treasurer).get("/api/feed?club=" + club.getId());
            assertThat(listed.has("$.data[0].recipients")).isFalse();
        }

        @Test
        void aPausedClubsPostsAreHiddenFromEveryoneOutsideTheClub() {
            assertThat(postAs(president, body("club", club.getId(), "title", "Open day")).status()).isEqualTo(201);
            assertThat(titles(outsider, "")).contains("Open day");

            pause(club);
            assertThat(titles(outsider, "")).doesNotContain("Open day");
            assertThat(titles(outsider, "?club=" + club.getId())).doesNotContain("Open day");
            assertThat(titles(member, "")).contains("Open day");
            assertThat(titles(mentor, "")).contains("Open day");

            Response refused = postAs(president, body("club", club.getId(), "title", "Still here"));
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.message()).isEqualTo("Only active clubs can post updates");
            assertThat(refused.errorCode()).isEqualTo("CLUB_NOT_ACTIVE");
        }

        @Test
        void postingIsValidatedWithTheOriginalMessages() {
            Response missing = postAs(president, body("club", club.getId()));
            assertThat(missing.status()).isEqualTo(400);
            assertThat(missing.message()).isEqualTo("Title must be 3-200 characters");
            assertThat(missing.errorCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(postAs(president, body("club", club.getId(), "title", "  ab  ")).message()).isEqualTo("Title must be 3-200 characters");
            assertThat(postAs(president, body("club", "nope", "title", "Hello there")).message()).isEqualTo("Club is required");
            assertThat(postAs(president, body("club", club.getId(), "title", "Hello there", "type", "EVENT")).message()).isEqualTo("Invalid post type");
            assertThat(postAs(president, body("club", club.getId(), "title", "Hello there", "image", "ftp://example.com/a.png")).message())
                    .isEqualTo("image must be an http(s) URL or an uploaded file");
            assertThat(postAs(president, body("club", club.getId(), "title", "Hello there", "audience", body("mode", "SOMEONE"))).message())
                    .isEqualTo("Choose who should get this");
            assertThat(postAs(president, body("club", club.getId(), "title", "Hello there", "visibility", "AUDIENCE")).message()).isEqualTo("Invalid value");
            assertThat(postAs(president, body("club", club.getId(), "title", "Hello there", "body", "x".repeat(4001))).message()).isEqualTo("Invalid value");
            assertThat(postAs(president, body("club", new ObjectId().toHexString(), "title", "Hello there")).status()).isEqualTo(404);

            Response noEvent = postAs(president, body("club", club.getId(), "type", "EVENT_UPDATE", "title", "Venue change"));
            assertThat(noEvent.message()).isEqualTo("Event updates must reference an event");
            Club other = data.activeClub("Drama Club", mentor, outsider);
            Event elsewhere = SocialFixtures.publishedEvent(mongo, other, "Open Mic");
            Response foreign = postAs(president, body("club", club.getId(), "type", "EVENT_UPDATE", "title", "Venue change", "event", elsewhere.getId()));
            assertThat(foreign.message()).isEqualTo("Updates can only reference this club's published events");

            // A club whose only follower is the person posting has nobody to tell.
            Club lonely = data.activeClub("Chess Club", mentor, marketing);
            Response nobody = postAs(marketing, body("club", lonely.getId(), "title", "Anyone?", "audience", body("mode", "FOLLOWERS")));
            assertThat(nobody.status()).isEqualTo(400);
            assertThat(nobody.message()).isEqualTo("Nobody matches this audience yet — choose other roles, departments or people");
        }

        @Test
        void aPostShowsItsClubAuthorAndAudienceLikeTheOriginal() {
            Response posted = postAs(marketing, body("club", club.getId(), "title", "  Hackathon on Friday  ", "body", "  Bring laptops.  ",
                    "image", "/uploads/feed/poster.png"));
            assertThat(posted.status()).isEqualTo(201);
            assertThat(posted.string("$.data.title")).isEqualTo("Hackathon on Friday");
            assertThat(posted.string("$.data.body")).isEqualTo("Bring laptops.");
            assertThat(posted.string("$.data.type")).isEqualTo("ANNOUNCEMENT");
            assertThat(posted.string("$.data.club._id")).isEqualTo(club.getId());
            assertThat(posted.string("$.data.club.name")).isEqualTo("Coding Club");
            assertThat(posted.string("$.data.club.status")).isEqualTo("ACTIVE");
            assertThat(posted.has("$.data.club.category")).isTrue();
            assertThat(posted.has("$.data.club.logo")).isTrue();
            assertThat(posted.string("$.data.author.name")).isEqualTo("Mark");
            assertThat((Boolean) posted.at("$.data.isSystem")).isFalse();
            assertThat(posted.has("$.data.system")).isFalse();
            assertThat(posted.string("$.data.visibility")).isEqualTo("PUBLIC");
            assertThat(posted.string("$.data.audience.mode")).isEqualTo("EVERYONE");
            assertThat((Object) posted.at("$.data.audience.label")).isNull();
            assertThat((Object) posted.at("$.data.recipientCount")).isNull();
            assertThat((Object) posted.at("$.data.event")).isNull();
            assertThat((Object) posted.at("$.data.result")).isNull();
            assertThat(posted.string("$.data.image")).isEqualTo("/uploads/feed/poster.png");
            assertThat(posted.has("$.data.createdAt")).isTrue();

            Document audit = mongo.findOne(Query.query(Criteria.where("action").is("FEED_POST_CREATED")), Document.class, "auditlogs");
            assertThat(audit.get("metadata", Document.class).getString("type")).isEqualTo("ANNOUNCEMENT");

            Notification notice = mongo.findOne(Query.query(Criteria.where("user").is(new ObjectId(member.id())).and("type").is("ANNOUNCEMENT")), Notification.class);
            assertThat(notice.getTitle()).isEqualTo("Coding Club: Hackathon on Friday");
            assertThat(notice.getMessage()).isEqualTo("  Bring laptops.  ");
            assertThat(notice.getLink()).isEqualTo("/clubs/" + club.getId());
        }

        @Test
        void eventUpdatesLinkToTheEventAndAreListedOnItsPage() {
            Event event = SocialFixtures.publishedEvent(mongo, club, "Hack Night");
            Response posted = postAs(president, body("club", club.getId(), "type", "EVENT_UPDATE", "title", "Room change", "event", event.getId()));
            assertThat(posted.status()).isEqualTo(201);
            assertThat(posted.string("$.data.event._id")).isEqualTo(event.getId());
            assertThat(posted.string("$.data.event.title")).isEqualTo("Hack Night");
            assertThat(posted.has("$.data.event.registeredCount")).isTrue();
            assertThat((Object) posted.at("$.data.event.venue")).isNull();

            assertThat(titles(outsider, "?event=" + event.getId() + "&types=EVENT_UPDATE")).containsExactly("Room change");
            Notification notice = mongo.findOne(Query.query(Criteria.where("user").is(new ObjectId(member.id())).and("type").is("ANNOUNCEMENT")), Notification.class);
            assertThat(notice.getLink()).isEqualTo("/events/" + event.getId());

            Response badQuery = api.as(outsider).get("/api/feed?event=nope");
            assertThat(badQuery.status()).isEqualTo(400);
            assertThat(badQuery.message()).isEqualTo("Invalid value");
        }

        @Test
        void myClubsShowsOnlyTheViewersClubsAndPagesNewestFirst() {
            Club other = data.activeClub("Drama Club", mentor, outsider);
            postAs(president, body("club", club.getId(), "title", "First news"));
            postAs(president, body("club", club.getId(), "title", "Second news"));
            postAs(outsider, body("club", other.getId(), "title", "Drama news"));

            assertThat(titles(member, "?scope=my-clubs")).containsExactly("Second news", "First news");
            Response page = api.as(member).get("/api/feed?limit=2");
            List<String> newestFirst = page.at("$.data[*].title");
            assertThat(newestFirst).containsExactly("Drama news", "Second news");
            assertThat((Map<String, Object>) page.at("$.meta")).isEqualTo(Map.of("total", 3, "page", 1, "limit", 2, "totalPages", 2));
            assertThat(titles(member, "?limit=2&page=2")).containsExactly("First news");
        }

        @Test
        void sendEmailFalseOnlyNotifiesInTheApp() {
            postAs(president, body("club", club.getId(), "title", "Quiet note", "sendEmail", false));
            assertThat(data.mailsTo(member.email())).isEmpty();
            assertThat(FeedIntegrationTest.this.got(member, "Coding Club: Quiet note")).isTrue();
        }

        @Test
        void thePreviewCountsTheWholeCampusForEveryoneAndOnlyClubOfficersMayAsk() {
            Response preview = api.as(president).post("/api/feed/audience-preview", body("club", club.getId(), "audience", body("mode", "EVERYONE")));
            long campus = mongo.count(Query.query(Criteria.where("isActive").is(true).and("isEmailVerified").is(true)), "users");
            assertThat(((Number) preview.at("$.data.count")).longValue()).isEqualTo(campus);
            assertThat((Integer) preview.at("$.data.email")).isEqualTo(4);
            assertThat((Object) preview.at("$.data.label")).isNull();

            assertThat(api.as(member).post("/api/feed/audience-preview", body("club", club.getId())).status()).isEqualTo(403);
            assertThat(api.as(president).post("/api/feed/audience-preview", body("club", "nope")).message()).isEqualTo("Club is required");
        }
    }
}
