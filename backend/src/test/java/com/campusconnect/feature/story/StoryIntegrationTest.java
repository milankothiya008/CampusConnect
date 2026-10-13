package com.campusconnect.feature.story;

import static com.campusconnect.feature.feed.SocialFixtures.MP4;
import static com.campusconnect.feature.feed.SocialFixtures.PNG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.social.Story;
import com.campusconnect.domain.social.StoryView;
import com.campusconnect.feature.feed.SocialFixtures;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Port of tests/integration/stories.test.js ("club stories"), plus the cases it does not cover. */
class StoryIntegrationTest extends IntegrationTest {

    @Autowired
    AppProperties properties;

    @Autowired
    StorySweeper sweeper;

    @Autowired
    StoryTray tray;

    TestUser mentor;
    TestUser president;
    TestUser vicePresident;
    TestUser marketer;
    TestUser member;
    TestUser student;
    TestUser otherPresident;
    Club club;
    Club otherClub;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
        mentor = data.faculty();
        president = data.student(s -> s.name("President"));
        vicePresident = data.student(s -> s.name("Vice President"));
        marketer = data.student(s -> s.name("Marketing"));
        member = data.student(s -> s.name("Member"));
        student = data.student(s -> s.name("Viewer Student").department("IT"));
        otherPresident = data.student(s -> s.name("Other President"));
        club = data.activeClub("Coding Club", mentor, president);
        otherClub = data.activeClub("Music Society", mentor, otherPresident);
        data.addMembership(club, vicePresident, "VICE_PRESIDENT");
        data.addMembership(club, marketer, "MARKETING_COORDINATOR");
        data.addMembership(club, member, "MEMBER");
    }

    // ---------------------------------------------------------------- helpers

    Response upload(TestUser user, String clubId, byte[] content, String name) {
        return SocialFixtures.upload(api, user, "/api/stories/media?club=" + clubId, content, name);
    }

    Response post(TestUser user, Map<String, Object> overrides) {
        Response uploaded = upload(user, club.getId(), PNG, "poster.png");
        assertThat(uploaded.status()).as(uploaded.toString()).isEqualTo(201);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("club", club.getId());
        body.put("media", uploaded.at("$.data"));
        body.put("caption", "Hack Night is tonight!");
        body.putAll(overrides);
        return api.as(user).post("/api/stories", body);
    }

    Response post(TestUser user) {
        return post(user, Map.of());
    }

    List<Map<String, Object>> trayFor(TestUser user) {
        Response response = api.as(user).get("/api/stories");
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.at("$.data");
    }

    static Map<String, Object> groupOf(List<Map<String, Object>> groups, String clubId) {
        return groups.stream().filter(group -> clubId.equals(((Map<?, ?>) group.get("club")).get("_id"))).findFirst().orElse(null);
    }

    Map<String, Object> groupOf(List<Map<String, Object>> groups) {
        return groupOf(groups, club.getId());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> firstStory(Map<String, Object> group) {
        return ((List<Map<String, Object>>) group.get("stories")).get(0);
    }

    Path localPath(String url) {
        return Path.of(properties.getUploads().getDir()).resolve(url.replaceFirst("^.*/uploads/", ""));
    }

    Story story(String id) {
        return mongo.findById(id, Story.class);
    }

    @Nested
    class Posting {

        @Test
        void withoutCloudCredentialsTheUploadTicketPointsAtDevelopmentStorage() {
            Response ticket = api.as(president).post("/api/stories/uploads", Map.of("club", club.getId(), "kind", "VIDEO"));
            assertThat(ticket.status()).isEqualTo(201);
            assertThat(ticket.message()).isEqualTo("Upload ready");
            assertThat(ticket.string("$.data.provider")).isEqualTo("local");
            assertThat(ticket.string("$.data.kind")).isEqualTo("VIDEO");
            assertThat(ticket.string("$.data.uploadUrl")).isEqualTo("/stories/media");
            assertThat((Integer) ticket.at("$.data.maxVideoSeconds")).isEqualTo(30);
        }

        @Test
        void thePresidentPostsAPhotoStoryTheFileGoesToMediaStorageOnlyItsDetailsToTheDatabase() {
            Response response = post(president);
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.message()).isEqualTo("Story posted");
            assertThat(response.string("$.data.kind")).isEqualTo("IMAGE");
            assertThat(response.string("$.data.caption")).isEqualTo("Hack Night is tonight!");
            assertThat((Integer) response.at("$.data.viewCount")).isZero();
            assertThat((Boolean) response.at("$.data.seen")).isFalse();
            assertThat(response.string("$.data.club.name")).isEqualTo("Coding Club");
            String url = response.string("$.data.url");
            assertThat(url).matches(".*/uploads/stories/[a-f0-9]{24}/.+\\.png$");
            assertThat(Files.exists(localPath(url))).isTrue();

            Story stored = story(response.string("$.data._id"));
            assertThat(stored.getMedia().getKind()).isEqualTo(MediaKind.IMAGE);
            assertThat(stored.getMedia().getProvider()).isEqualTo("local");
            assertThat(stored.getMedia().getFormat()).isEqualTo("png");
            assertThat(mongo.findById(new ObjectId(stored.getId()), Document.class, "stories").toJson()).doesNotContain("base64");
            double hours = Duration.between(stored.getCreatedAt(), stored.getExpiresAt()).toMillis() / 3_600_000d;
            assertThat(hours).isCloseTo(24, within(0.05));
        }

        @Test
        void videosAreAcceptedAndRecognisedFromTheirBytes() {
            Response uploaded = upload(president, club.getId(), MP4, "clip.mp4");
            assertThat(uploaded.status()).isEqualTo(201);
            assertThat(uploaded.message()).isEqualTo("Media uploaded");
            assertThat(uploaded.string("$.data.kind")).isEqualTo("VIDEO");
            assertThat(uploaded.string("$.data.format")).isEqualTo("mp4");

            Map<String, Object> media = new LinkedHashMap<>(uploaded.at("$.data"));
            media.put("duration", 12.4);
            Response response = api.as(president).post("/api/stories", Map.of("club", club.getId(), "media", media));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.string("$.data.kind")).isEqualTo("VIDEO");
            assertThat((Double) response.at("$.data.duration")).isEqualTo(12.4);
        }

        @Test
        void filesThatAreNotPhotosOrVideosAreRefusedWhateverTheirNameSays() {
            Response response = upload(president, club.getId(), "definitely not an image, just some text".getBytes(StandardCharsets.UTF_8), "fake.png");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.message()).contains("Only JPEG, PNG or WebP photos and MP4, MOV or WebM videos");
            assertThat(response.errorCode()).isEqualTo("UPLOAD_ERROR");
        }

        @Test
        void officersWhoPostUpdatesCanAddStoriesPlainMembersOtherClubsAndFacultyCannot() {
            assertThat(post(vicePresident).status()).isEqualTo(201);
            assertThat(post(marketer).status()).isEqualTo(201);

            for (TestUser outsider : List.of(member, student, otherPresident, mentor)) {
                Response ticket = api.as(outsider).post("/api/stories/uploads", Map.of("club", club.getId(), "kind", "IMAGE"));
                assertThat(ticket.status()).as(outsider.name()).isEqualTo(403);
                assertThat(ticket.message()).isEqualTo("Only club officers who post updates can add stories for this club");
                assertThat(upload(outsider, club.getId(), PNG, "x.png").status()).as(outsider.name()).isEqualTo(403);
            }
        }

        @Test
        void anUploadCanOnlyBeUsedByThePersonAndClubItWasIssuedForAndCannotBeTamperedWith() {
            Map<String, Object> uploaded = upload(president, club.getId(), PNG, "poster.png").at("$.data");

            Response otherPerson = api.as(vicePresident).post("/api/stories", Map.of("club", club.getId(), "media", uploaded));
            assertThat(otherPerson.status()).isEqualTo(400);
            assertThat(otherPerson.message()).isEqualTo("This upload was not issued for this club");

            String[] parts = String.valueOf(uploaded.get("token")).split("\\.");
            Map<String, Object> altered = json.readValue(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), Map.class);
            altered.put("k", "../../server.js");
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(altered).getBytes(StandardCharsets.UTF_8)) + "." + parts[1];
            Map<String, Object> tampered = new LinkedHashMap<>(uploaded);
            tampered.put("token", forged);
            assertThat(api.as(president).post("/api/stories", Map.of("club", club.getId(), "media", tampered)).status()).isEqualTo(400);

            Map<String, Object> cloud = Map.of("provider", "cloudinary", "publicId", "x", "version", 1, "signature", "y", "kind", "IMAGE");
            assertThat(api.as(president).post("/api/stories", Map.of("club", club.getId(), "media", cloud)).status()).isEqualTo(400);
        }

        @Test
        void anEventsPosterCanBeSharedToTheStoryWithoutUploadingAnything() {
            Event event = SocialFixtures.event(mongo, club, "Hack Night", EventStatus.PUBLISHED,
                    e -> e.setPoster("https://cdn.example.com/hacknight.jpg"));

            Response shared = api.as(president).post("/api/stories", Map.of("club", club.getId(), "event", event.getId(), "media", Map.of("provider", "event"),
                    "caption", "Registrations open"));
            assertThat(shared.status()).isEqualTo(201);
            assertThat(shared.string("$.data.kind")).isEqualTo("IMAGE");
            assertThat(shared.string("$.data.url")).isEqualTo("https://cdn.example.com/hacknight.jpg");
            assertThat(shared.string("$.data.event._id")).isEqualTo(event.getId());
            assertThat(shared.string("$.data.event.title")).isEqualTo("Hack Night");

            // Deleting the story never deletes the event's poster.
            assertThat(api.as(president).delete("/api/stories/" + shared.string("$.data._id")).status()).isEqualTo(200);
            assertThat(mongo.count(new Query(), Story.class)).isZero();
        }

        @Test
        void aClubCanHaveALimitedNumberOfLiveStories() {
            int previous = properties.getStories().getMaxPerOwner();
            properties.getStories().setMaxPerOwner(2);
            try {
                post(president);
                post(president);
                Response third = api.as(president).post("/api/stories/uploads", Map.of("club", club.getId(), "kind", "IMAGE"));
                assertThat(third.status()).isEqualTo(409);
                assertThat(third.message()).contains("up to 2 live stories");
            } finally {
                properties.getStories().setMaxPerOwner(previous);
            }
        }
    }

    @Nested
    class Watching {

        String storyId;

        @BeforeEach
        void postStory() {
            storyId = post(president).string("$.data._id");
        }

        @Test
        void everyoneSignedInSeesTheClubsRingCountsAreOnlyShownToTheClubsManagers() {
            Map<String, Object> studentGroup = groupOf(trayFor(student));
            assertThat(studentGroup).containsEntry("canManage", false).containsEntry("allSeen", false);
            assertThat(((Map<?, ?>) studentGroup.get("club")).get("name")).isEqualTo("Coding Club");
            Map<String, Object> seenByStudent = firstStory(studentGroup);
            assertThat(seenByStudent).containsEntry("_id", storyId).containsEntry("seen", false).containsEntry("liked", false);
            assertThat(seenByStudent).doesNotContainKeys("viewCount", "likeCount", "author", "club");

            Map<String, Object> presidentGroup = groupOf(trayFor(president));
            assertThat(presidentGroup).containsEntry("canManage", true);
            assertThat(firstStory(presidentGroup)).containsEntry("viewCount", 0);
        }

        @Test
        void watchingMarksTheRingAsSeenAndCountsEachPersonOnceTheAuthorsOwnViewsDontCount() {
            Response viewed = api.as(student).post("/api/stories/" + storyId + "/view");
            assertThat(viewed.status()).isEqualTo(200);
            assertThat(viewed.message()).isEqualTo("Story viewed");
            assertThat((Boolean) viewed.at("$.data.seen")).isTrue();
            api.as(student).post("/api/stories/" + storyId + "/view");
            api.as(member).post("/api/stories/" + storyId + "/view");
            api.as(president).post("/api/stories/" + storyId + "/view");

            Map<String, Object> studentGroup = groupOf(trayFor(student));
            assertThat(studentGroup).containsEntry("allSeen", true);
            assertThat(firstStory(studentGroup)).containsEntry("seen", true);
            assertThat(story(storyId).getViewCount()).isEqualTo(2);

            Map<String, Object> presidentStory = firstStory(groupOf(trayFor(president)));
            assertThat(presidentStory).containsEntry("seen", true).containsEntry("viewCount", 2);
        }

        @Test
        void viewersCanLikeAStoryOnceAndTakeTheLikeBack() {
            Response liked = api.as(student).post("/api/stories/" + storyId + "/like", Map.of("liked", true));
            assertThat(liked.message()).isEqualTo("Story liked");
            assertThat((Map<String, Object>) liked.at("$.data")).isEqualTo(Map.of("liked", true));
            api.as(student).post("/api/stories/" + storyId + "/like", Map.of("liked", true));
            assertThat(story(storyId).getLikeCount()).isEqualTo(1);
            assertThat(firstStory(groupOf(trayFor(student)))).containsEntry("seen", true).containsEntry("liked", true);

            Response unliked = api.as(student).post("/api/stories/" + storyId + "/like", Map.of("liked", false));
            assertThat(unliked.message()).isEqualTo("Like removed");
            assertThat(story(storyId).getLikeCount()).isZero();

            Response own = api.as(president).post("/api/stories/" + storyId + "/like", Map.of("liked", true));
            assertThat(own.status()).isEqualTo(400);
            assertThat(own.message()).isEqualTo("You can't like your own club's story");
        }

        @Test
        void thePresidentSeesWhoWatchedNewestFirstWithLikesOtherPeopleCannot() {
            api.as(member).post("/api/stories/" + storyId + "/view");
            api.as(student).post("/api/stories/" + storyId + "/like", Map.of("liked", true));
            api.as(president).post("/api/stories/" + storyId + "/view");

            Response viewers = api.as(president).get("/api/stories/" + storyId + "/viewers");
            assertThat(viewers.status()).isEqualTo(200);
            assertThat(viewers.message()).isEqualTo("Viewers fetched");
            List<String> names = viewers.at("$.data[*].user.name");
            List<Boolean> likes = viewers.at("$.data[*].liked");
            assertThat(names).containsExactly("Viewer Student", "Member");
            assertThat(likes).containsExactly(true, false);
            assertThat((Integer) viewers.at("$.meta.total")).isEqualTo(2);
            assertThat((Integer) viewers.at("$.meta.viewCount")).isEqualTo(2);
            assertThat((Integer) viewers.at("$.meta.likeCount")).isEqualTo(1);
            assertThat(viewers.has("$.data[0].user.departmentCode")).isTrue();

            assertThat(api.as(marketer).get("/api/stories/" + storyId + "/viewers").status()).isEqualTo(200);
            assertThat(api.as(student).get("/api/stories/" + storyId + "/viewers").status()).isEqualTo(403);
            assertThat(api.as(member).get("/api/stories/" + storyId + "/viewers").status()).isEqualTo(403);
        }

        @Test
        void clubsTheViewerManagesComeFirstThenUnwatchedRings() {
            Map<String, Object> music = upload(otherPresident, otherClub.getId(), PNG, "m.png").at("$.data");
            assertThat(api.as(otherPresident).post("/api/stories", Map.of("club", otherClub.getId(), "media", music)).status()).isEqualTo(201);

            api.as(student).post("/api/stories/" + storyId + "/view");
            List<String> order = trayFor(student).stream().map(group -> (String) ((Map<?, ?>) group.get("club")).get("name")).toList();
            assertThat(order).containsExactly("Music Society", "Coding Club");

            assertThat(((Map<?, ?>) trayFor(president).get(0).get("club")).get("name")).isEqualTo("Coding Club");
        }

        @Test
        void simultaneousLikesFromOnePersonCountOnce() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                List<Callable<Response>> taps = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    taps.add(() -> api.as(student).post("/api/stories/" + storyId + "/like", Map.of("liked", true)));
                }
                for (Future<Response> tap : pool.invokeAll(taps)) {
                    assertThat(tap.get().status()).isEqualTo(200);
                }
            } finally {
                pool.shutdown();
            }
            assertThat(story(storyId).getLikeCount()).isEqualTo(1);
            assertThat(story(storyId).getViewCount()).isEqualTo(1);
            assertThat(mongo.count(Query.query(Criteria.where("story").is(new ObjectId(storyId))), StoryView.class)).isEqualTo(1);
        }
    }

    @Nested
    class DisappearingAfter24Hours {

        @Test
        void expiredStoriesLeaveTheTrayAtOnceAndTheSweeperDeletesTheFileTheStoryAndItsViews() {
            Map<String, Object> created = post(president).at("$.data");
            String id = (String) created.get("_id");
            Path file = localPath((String) created.get("url"));
            api.as(student).post("/api/stories/" + id + "/view");
            assertThat(Files.exists(file)).isTrue();

            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(id))), new Update().set("expiresAt", Instant.now().minusSeconds(1)), Story.class);
            tray.invalidate();

            assertThat(groupOf(trayFor(student))).isNull();
            Response late = api.as(student).post("/api/stories/" + id + "/view");
            assertThat(late.status()).isEqualTo(404);
            assertThat(late.message()).isEqualTo("This story is no longer available");

            assertThat(sweeper.sweepExpiredStories()).isEqualTo(new StoryService.SweepResult(1, 0));
            assertThat(story(id)).isNull();
            assertThat(mongo.count(Query.query(Criteria.where("story").is(new ObjectId(id))), StoryView.class)).isZero();
            assertThat(Files.exists(file)).isFalse();
        }

        @Test
        void managersCanDeleteAStoryEarlyWhichRemovesItsFileStraightAway() {
            Map<String, Object> created = post(president).at("$.data");
            String id = (String) created.get("_id");
            Response refused = api.as(student).delete("/api/stories/" + id);
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.message()).isEqualTo("Only the club's story managers can delete this story");

            Response deleted = api.as(vicePresident).delete("/api/stories/" + id);
            assertThat(deleted.status()).isEqualTo(200);
            assertThat(deleted.message()).isEqualTo("Story deleted");
            assertThat(Files.exists(localPath((String) created.get("url")))).isFalse();
            assertThat(groupOf(trayFor(student))).isNull();
        }

        @Test
        void theSweeperRetriesAFileItCannotRemoveAndGivesUpAfterFiveAttempts() {
            // Cloud media cannot be removed while Cloudinary is not configured, so every attempt fails.
            Story stuck = new Story();
            stuck.setClub(club.getId());
            stuck.setAuthor(president.id());
            MediaFile media = new MediaFile();
            media.setKind(MediaKind.IMAGE);
            media.setProvider("cloudinary");
            media.setKey("campusconnect/stories/" + club.getId() + "/abc_def");
            stuck.setMedia(media);
            stuck.setExpiresAt(Instant.now().minusSeconds(60));
            stuck = mongo.insert(stuck);

            for (int attempt = 1; attempt <= 4; attempt++) {
                assertThat(sweeper.sweepExpiredStories()).isEqualTo(new StoryService.SweepResult(0, 1));
                assertThat(story(stuck.getId()).getCleanupAttempts()).isEqualTo(attempt);
            }
            assertThat(sweeper.sweepExpiredStories()).isEqualTo(new StoryService.SweepResult(1, 0));
            assertThat(story(stuck.getId())).isNull();
        }
    }

    @Nested
    class BeyondTheOriginalTests {

        @Test
        void aSuspendedClubsStoriesLeaveTheTray() {
            post(president);
            assertThat(groupOf(trayFor(student))).isNotNull();

            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(club.getId()))), new Update().set("status", "SUSPENDED"), Club.class);
            tray.invalidate();
            assertThat(groupOf(trayFor(student))).isNull();
            assertThat(groupOf(trayFor(president))).isNull();

            Response ticket = api.as(president).post("/api/stories/uploads", Map.of("club", club.getId(), "kind", "IMAGE"));
            assertThat(ticket.status()).isEqualTo(409);
            assertThat(ticket.errorCode()).isEqualTo("CLUB_NOT_ACTIVE");
        }

        @Test
        void requestsAreValidatedWithTheOriginalMessages() {
            Response empty = api.as(president).post("/api/stories", Map.of());
            assertThat(empty.status()).isEqualTo(400);
            assertThat(empty.message()).isEqualTo("Choose a club. Add a photo or video");
            assertThat(empty.errorCode()).isEqualTo("VALIDATION_ERROR");

            assertThat(api.as(president).post("/api/stories/uploads", Map.of("club", club.getId(), "kind", "AUDIO")).message()).isEqualTo("Choose a photo or a video");
            assertThat(api.as(president).post("/api/stories/uploads", Map.of("club", "nope", "kind", "IMAGE")).message()).isEqualTo("Choose a club");
            assertThat(upload(president, "nope", PNG, "x.png").message()).isEqualTo("Choose a club");

            Map<String, Object> media = upload(president, club.getId(), PNG, "x.png").at("$.data");
            Response longCaption = api.as(president).post("/api/stories", Map.of("club", club.getId(), "media", media, "caption", "x".repeat(201)));
            assertThat(longCaption.message()).isEqualTo("Captions can be up to 200 characters");
            Response badEvent = api.as(president).post("/api/stories", Map.of("club", club.getId(), "media", media, "event", "nope"));
            assertThat(badEvent.message()).isEqualTo("Invalid event");

            String storyId = post(president).string("$.data._id");
            assertThat(api.as(student).post("/api/stories/" + storyId + "/like", Map.of()).message()).isEqualTo("liked must be true or false");
            assertThat(api.as(student).post("/api/stories/not-an-id/view").message()).isEqualTo("Invalid id");
            assertThat(api.as(student).post("/api/stories/" + new ObjectId() + "/view").status()).isEqualTo(404);
        }

        @Test
        void storiesLinkOnlyThisClubsPublishedEvents() {
            Event draft = SocialFixtures.event(mongo, club, "Secret draft", EventStatus.DRAFT, e -> e.setPoster("https://cdn.example.com/p.jpg"));
            Event elsewhere = SocialFixtures.publishedEvent(mongo, otherClub, "Open Mic");
            Event noPoster = SocialFixtures.publishedEvent(mongo, club, "No Poster");
            for (Event event : List.of(draft, elsewhere)) {
                Response refused = api.as(president).post("/api/stories", Map.of("club", club.getId(), "event", event.getId(), "media", Map.of("provider", "event")));
                assertThat(refused.status()).isEqualTo(400);
                assertThat(refused.message()).isEqualTo("Link a published event of this club");
            }
            Response bare = api.as(president).post("/api/stories", Map.of("club", club.getId(), "event", noPoster.getId(), "media", Map.of("provider", "event")));
            assertThat(bare.message()).isEqualTo("That event has no poster to share");
        }

        @Test
        void signedOutVisitorsCannotOpenTheTray() {
            assertThat(api.get("/api/stories").status()).isEqualTo(401);
        }
    }
}
