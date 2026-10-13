package com.campusconnect.feature.gallery;

import static com.campusconnect.feature.feed.SocialFixtures.MP4;
import static com.campusconnect.feature.feed.SocialFixtures.PNG;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.audit.AuditLog;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventMedia;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.domain.event.UploaderRole;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.feed.SocialFixtures;
import com.campusconnect.feature.like.LikeService;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Port of tests/integration/gallery.test.js ("event gallery"). The Node tests build on each other inside a describe;
 * here every test starts from a clean database, so each one first creates what the earlier tests left behind.
 */
class GalleryIntegrationTest extends IntegrationTest {

    @Autowired
    AppProperties properties;

    @Autowired
    LikeService likes;

    TestUser mentor;
    TestUser president;
    TestUser vice;
    TestUser coordinator;
    TestUser member;
    TestUser asha;
    TestUser bina;
    TestUser outsider;
    Club club;
    Club otherClub;
    Event walk;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
        mentor = data.faculty();
        president = data.student(s -> s.name("President"));
        vice = data.student(s -> s.name("Vice President"));
        coordinator = data.student(s -> s.name("Coordinator"));
        member = data.student(s -> s.name("Plain Member"));
        asha = data.student(s -> s.name("Asha Patel"));
        bina = data.student(s -> s.name("Bina Shah"));
        outsider = data.student(s -> s.name("Outsider"));
        club = data.activeClub("Photo Club", mentor, president);
        otherClub = data.activeClub("Chess Club", mentor, outsider);
        data.addMembership(club, vice, "VICE_PRESIDENT");
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        data.addMembership(club, member, "MEMBER");

        walk = SocialFixtures.publishedEvent(mongo, club, "Heritage Walk");
        SocialFixtures.register(mongo, walk, asha);
        SocialFixtures.register(mongo, walk, bina);
    }

    // ---------------------------------------------------------------- helpers

    void startEvent(Event event) {
        SocialFixtures.setEventTimes(mongo, event, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(3, ChronoUnit.HOURS));
    }

    Response uploadFile(TestUser user, String eventId, byte[] content, String name) {
        return SocialFixtures.upload(api, user, "/api/events/" + eventId + "/gallery/media", content, name);
    }

    /** The full browser flow in development storage: ticket, file, attach. */
    Response add(TestUser user, String eventId, byte[] content, String name) {
        Response tickets = api.as(user).post("/api/events/" + eventId + "/gallery/uploads", Map.of("kinds", List.of(content == MP4 ? "VIDEO" : "IMAGE")));
        if (tickets.status() != 201) {
            return tickets;
        }
        Response uploaded = uploadFile(user, eventId, content, name);
        if (uploaded.status() != 201) {
            return uploaded;
        }
        Map<String, Object> media = new LinkedHashMap<>(uploaded.at("$.data"));
        media.put("width", 1200);
        media.put("height", 800);
        return api.as(user).post("/api/events/" + eventId + "/gallery", Map.of("media", media));
    }

    Response add(TestUser user) {
        return add(user, walk.getId(), PNG, "photo.png");
    }

    Response gallery(TestUser user, String eventId) {
        return (user == null ? api : api.as(user)).get("/api/events/" + eventId + "/gallery");
    }

    Response gallery(TestUser user) {
        return gallery(user, walk.getId());
    }

    /** Asha, checked in after the event started, adds a video: it waits for review. */
    Response ashaVideo() {
        startEvent(walk);
        SocialFixtures.checkIn(mongo, walk, asha, president);
        Response added = add(asha, walk.getId(), MP4, "clip.mp4");
        assertThat(added.status()).as(added.toString()).isEqualTo(201);
        return added;
    }

    /** The member's photo and Asha's video, both waiting for review (the state after "who can upload"). */
    void twoPendingUploads() {
        assertThat(add(member).status()).isEqualTo(201);
        ashaVideo();
    }

    List<Notification> notices(String type) {
        return mongo.find(Query.query(Criteria.where("type").is(type)).with(Sort.by(Sort.Direction.ASC, "createdAt")), Notification.class);
    }

    Notification notice(TestUser user, String type) {
        return mongo.findOne(Query.query(Criteria.where("type").is(type).and("user").is(new ObjectId(user.id()))), Notification.class);
    }

    List<String> pendingIdsOf(String uploaderName) {
        List<Map<String, Object>> pending = gallery(vice).at("$.data.pending");
        return pending.stream().filter(item -> uploaderName.equals(((Map<?, ?>) item.get("uploader")).get("name"))).map(item -> (String) item.get("_id")).toList();
    }

    /** Two approved photos by the member (approved by the VP), Asha's video still waiting. */
    void reviewedState() {
        twoPendingUploads();
        assertThat(add(member).status()).isEqualTo(201);
        Response approved = api.as(vice).post("/api/events/" + walk.getId() + "/gallery/approve", Map.of("ids", pendingIdsOf("Plain Member")));
        assertThat(approved.status()).isEqualTo(200);
    }

    @Nested
    class WhoCanUploadAndWhen {

        @Test
        void clubMembersCanAddPhotosAsSoonAsTheEventIsPublishedTheGallerySaysSo() {
            Response view = gallery(member);
            assertThat(view.status()).isEqualTo(200);
            assertThat(view.message()).isEqualTo("Gallery fetched");
            assertThat((Boolean) view.at("$.data.viewer.canUpload")).isTrue();
            assertThat((Boolean) view.at("$.data.viewer.canModerate")).isFalse();
            assertThat(view.string("$.data.viewer.role")).isEqualTo("MEMBER");
            assertThat((Integer) view.at("$.data.viewer.limits.maxPendingPerUser")).isEqualTo(30);

            Response added = add(member);
            assertThat(added.status()).isEqualTo(201);
            assertThat(added.message()).isEqualTo("Sent for review");
            assertThat(added.string("$.data.kind")).isEqualTo("IMAGE");
            assertThat(added.string("$.data.status")).isEqualTo("PENDING");
            assertThat((Boolean) added.at("$.data.mine")).isTrue();
            assertThat((Boolean) added.at("$.data.canDelete")).isTrue();
            assertThat(added.string("$.data.uploaderRole")).isEqualTo("MEMBER");
            assertThat(added.string("$.data.url")).matches(".*/uploads/gallery/[a-f0-9]{24}/.+\\.png$");
            assertThat((Integer) added.at("$.data.width")).isEqualTo(1200);
        }

        @Test
        void registeredStudentsCantUploadBeforeTheEventStartsOrBeforeTheyreCheckedIn() {
            Response view = gallery(asha);
            assertThat((Boolean) view.at("$.data.viewer.canUpload")).isFalse();
            assertThat(view.string("$.data.viewer.hint")).contains("once the event starts");
            assertThat(add(asha).status()).isEqualTo(403);

            startEvent(walk);
            view = gallery(asha);
            assertThat(view.string("$.data.viewer.hint")).contains("Get checked in");
            assertThat(add(asha).status()).isEqualTo(403);

            // Bina, registered but never checked in, loses the hint once the event is over.
            SocialFixtures.setEventTimes(mongo, walk, null, Instant.now().minusSeconds(60));
            assertThat((Object) gallery(bina).at("$.data.viewer.hint")).isNull();
            SocialFixtures.setEventTimes(mongo, walk, null, Instant.now().plus(3, ChronoUnit.HOURS));

            SocialFixtures.checkIn(mongo, walk, asha, president);
            view = gallery(asha);
            assertThat((Boolean) view.at("$.data.viewer.canUpload")).isTrue();
            assertThat(view.string("$.data.viewer.role")).isEqualTo("PARTICIPANT");
            Response added = add(asha, walk.getId(), MP4, "clip.mp4");
            assertThat(added.status()).isEqualTo(201);
            assertThat(added.string("$.data.kind")).isEqualTo("VIDEO");
            assertThat(added.string("$.data.status")).isEqualTo("PENDING");
            assertThat(added.string("$.data.uploaderRole")).isEqualTo("PARTICIPANT");
        }

        @Test
        void outsidersAndSignedOutVisitorsCantUpload() {
            Response view = gallery(outsider);
            assertThat((Boolean) view.at("$.data.viewer.canUpload")).isFalse();
            assertThat((Object) view.at("$.data.viewer.hint")).isNull();
            Response refused = add(outsider);
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.message()).contains("Only club members and checked-in participants");

            Response anonymous = gallery(null);
            assertThat(anonymous.status()).isEqualTo(200);
            assertThat((Boolean) anonymous.at("$.data.viewer.canUpload")).isFalse();
            assertThat((Boolean) anonymous.at("$.data.viewer.canModerate")).isFalse();
            assertThat(api.post("/api/events/" + walk.getId() + "/gallery/uploads", Map.of("kinds", List.of("IMAGE"))).status()).isEqualTo(401);
        }

        @Test
        void anUploadIssuedForOneEventOrPersonCantBeAttachedElsewhere() {
            Event other = SocialFixtures.publishedEvent(mongo, club, "Other Event");
            Map<String, Object> uploaded = uploadFile(member, walk.getId(), PNG, "photo.png").at("$.data");
            assertThat(api.as(member).post("/api/events/" + other.getId() + "/gallery", Map.of("media", uploaded)).message()).contains("not issued for this event");
            assertThat(api.as(coordinator).post("/api/events/" + walk.getId() + "/gallery", Map.of("media", uploaded)).message()).contains("not issued for this event");
        }

        @Test
        void draftsHaveNoGallery() {
            Event draft = SocialFixtures.event(mongo, club, "Draft Event", EventStatus.DRAFT, null);
            Response view = gallery(member, draft.getId());
            assertThat((List<?>) view.at("$.data.items")).isEmpty();
            assertThat((Boolean) view.at("$.data.viewer.canUpload")).isFalse();
            assertThat(view.string("$.data.viewer.reason")).isEqualTo("Photos can be added once the event is published.");
            assertThat(add(member, draft.getId(), PNG, "photo.png").status()).isEqualTo(403);
        }

        @Test
        void oneRequestPreparesUpTo20UploadsAndANonMediaKindIsRefused() {
            Response tickets = api.as(member).post("/api/events/" + walk.getId() + "/gallery/uploads", Map.of("kinds", List.of("IMAGE", "IMAGE", "VIDEO")));
            assertThat(tickets.status()).isEqualTo(201);
            assertThat(tickets.message()).isEqualTo("Uploads ready");
            assertThat((List<?>) tickets.at("$.data")).hasSize(3);
            assertThat(tickets.string("$.data[0].provider")).isEqualTo("local");
            assertThat(tickets.string("$.data[0].uploadUrl")).isEqualTo("/events/" + walk.getId() + "/gallery/media");

            List<String> tooMany = new ArrayList<>();
            for (int i = 0; i < 21; i++) {
                tooMany.add("IMAGE");
            }
            Response many = api.as(member).post("/api/events/" + walk.getId() + "/gallery/uploads", Map.of("kinds", tooMany));
            assertThat(many.status()).isEqualTo(400);
            assertThat(many.message()).isEqualTo("Choose up to 20 photos or videos at a time");
            Response pdf = api.as(member).post("/api/events/" + walk.getId() + "/gallery/uploads", Map.of("kinds", List.of("PDF")));
            assertThat(pdf.status()).isEqualTo(400);
            assertThat(pdf.message()).isEqualTo("Only photos and videos can be added");
            assertThat(uploadFile(member, walk.getId(), "not an image at all, sorry".getBytes(StandardCharsets.UTF_8), "x.png").status()).isEqualTo(400);
        }
    }

    @Nested
    class Review {

        @Test
        void pendingUploadsAreHiddenFromEveryoneButTheirUploaderAndThePresidentVp() {
            twoPendingUploads();

            Response publicView = gallery(bina);
            assertThat((List<?>) publicView.at("$.data.items")).isEmpty();
            assertThat((List<?>) publicView.at("$.data.pending")).isEmpty();
            assertThat((List<?>) publicView.at("$.data.mine")).isEmpty();

            Response mine = gallery(asha);
            assertThat((List<?>) mine.at("$.data.mine")).hasSize(1);
            assertThat((Integer) mine.at("$.data.counts.pending")).isEqualTo(1);

            Response review = gallery(vice);
            assertThat((Boolean) review.at("$.data.viewer.canModerate")).isTrue();
            List<String> uploaders = review.at("$.data.pending[*].uploader.name");
            assertThat(uploaders).containsExactlyInAnyOrder("Asha Patel", "Plain Member");
            assertThat((Integer) review.at("$.data.counts.pending")).isEqualTo(2);
        }

        @Test
        void thePresidentAndVpAreToldOncePerBurstOfUploads() {
            twoPendingUploads();

            List<Notification> notices = notices("GALLERY_SUBMITTED");
            List<Notification> forPresident = notices.stream().filter(notice -> notice.getUser().equals(president.id())).toList();
            assertThat(forPresident).hasSize(2); // one for the member, one for Asha
            assertThat(notices).anyMatch(notice -> notice.getUser().equals(vice.id()));
            assertThat(notices).noneMatch(notice -> notice.getUser().equals(coordinator.id()));
            assertThat(forPresident.get(0).getLink()).isEqualTo("/gallery/" + walk.getId() + "?review=1");
            assertThat(forPresident.get(0).getTitle()).isEqualTo("New photos to review for Heritage Walk");

            add(member);
            assertThat(mongo.count(Query.query(Criteria.where("type").is("GALLERY_SUBMITTED").and("user").is(new ObjectId(president.id()))), Notification.class)).isEqualTo(2);
        }

        @Test
        void officersWithoutThePermissionCantApproveTheVpCanAndUploadersAreNotified() {
            twoPendingUploads();
            add(member);
            List<String> memberItems = pendingIdsOf("Plain Member");

            assertThat(api.as(coordinator).post("/api/events/" + walk.getId() + "/gallery/approve", Map.of("ids", memberItems)).status()).isEqualTo(403);
            assertThat(api.as(mentor).post("/api/events/" + walk.getId() + "/gallery/approve", Map.of("ids", memberItems)).status()).isEqualTo(403);

            Response approved = api.as(vice).post("/api/events/" + walk.getId() + "/gallery/approve", Map.of("ids", memberItems));
            assertThat(approved.status()).isEqualTo(200);
            assertThat(approved.message()).isEqualTo("Approved 2 — they're now in the gallery");
            assertThat((Integer) approved.at("$.data.approved")).isEqualTo(2);
            assertThat((Integer) approved.at("$.data.counts.approved")).isEqualTo(2);
            assertThat((Integer) approved.at("$.data.counts.pending")).isEqualTo(1);

            Notification notice = notice(member, "GALLERY_APPROVED");
            assertThat(notice.getTitle()).isEqualTo("Your uploads are in the Heritage Walk gallery");
            assertThat(notice.getMessage()).isEqualTo("2 photos you added are now on the event page.");
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("GALLERY_MEDIA_APPROVED").and("actor").is(new ObjectId(vice.id()))), AuditLog.class)).isTrue();

            Response publicView = gallery(null);
            assertThat((List<?>) publicView.at("$.data.items")).hasSize(2);
            assertThat(publicView.string("$.data.items[0].status")).isEqualTo("APPROVED");
            assertThat((Boolean) publicView.at("$.data.items[0].mine")).isFalse();
            assertThat((Boolean) publicView.at("$.data.items[0].canDelete")).isFalse();
            assertThat((Integer) publicView.at("$.data.counts.approved")).isEqualTo(2);
        }

        @Test
        void rejectingDeletesTheUploadAndTellsTheUploaderWhy() {
            reviewedState();
            String video = gallery(president).string("$.data.pending[0]._id");

            Response rejected = api.as(president).post("/api/events/" + walk.getId() + "/gallery/reject", Map.of("ids", List.of(video), "reason", "Blurry"));
            assertThat(rejected.status()).isEqualTo(200);
            assertThat(rejected.message()).isEqualTo("Declined and deleted");
            assertThat((Integer) rejected.at("$.data.rejected")).isEqualTo(1);
            assertThat(mongo.exists(Query.query(Criteria.where("_id").is(new ObjectId(video))), EventMedia.class)).isFalse();

            assertThat(notice(asha, "GALLERY_REJECTED").getMessage()).isEqualTo("The club didn't add 1 video you uploaded. Reason: Blurry");
            assertThat((List<?>) gallery(asha).at("$.data.mine")).isEmpty();
        }

        @Test
        void reviewingAgainOrItemsFromAnotherEventDoesNothing() {
            reviewedState();
            String item = gallery(null).string("$.data.items[0]._id");
            Response again = api.as(president).post("/api/events/" + walk.getId() + "/gallery/approve", Map.of("ids", List.of(item)));
            assertThat((Integer) again.at("$.data.approved")).isZero();
            Response invalid = api.as(president).post("/api/events/" + walk.getId() + "/gallery/approve", Map.of("ids", List.of("not-an-id")));
            assertThat(invalid.status()).isEqualTo(400);
            assertThat(invalid.message()).isEqualTo("Invalid selection");

            Event other = SocialFixtures.publishedEvent(mongo, club, "Other Event");
            String waiting = gallery(president).string("$.data.pending[0]._id");
            assertThat((Integer) api.as(president).post("/api/events/" + other.getId() + "/gallery/approve", Map.of("ids", List.of(waiting))).at("$.data.approved")).isZero();
        }

        @Test
        void thePresidentsAndVpsOwnUploadsGoStraightIntoTheGallery() {
            reviewedState();
            Response added = add(president);
            assertThat(added.message()).isEqualTo("Added to the gallery");
            assertThat(added.string("$.data.status")).isEqualTo("APPROVED");
            assertThat((Integer) gallery(null).at("$.data.counts.approved")).isEqualTo(3);
        }
    }

    @Nested
    class TheGallerySection {

        /** Heritage Walk with three approved photos, "Other Event" without photos, and a draft. */
        void section() {
            reviewedState();
            assertThat(add(president).status()).isEqualTo(201);
            SocialFixtures.publishedEvent(mongo, club, "Other Event");
            SocialFixtures.event(mongo, club, "Draft Event", EventStatus.DRAFT, null);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> card(Response list, String title) {
            List<Map<String, Object>> cards = list.at("$.data");
            return cards.stream().filter(card -> title.equals(((Map<String, Object>) card.get("event")).get("title"))).findFirst().orElse(null);
        }

        @Test
        void listsPublicEventsWithTheirPhotoAndVideoCountsCoverAndSearch() {
            section();
            Response list = api.get("/api/gallery");
            assertThat(list.status()).isEqualTo(200);
            assertThat(list.message()).isEqualTo("Galleries fetched");
            Map<String, Object> walkCard = card(list, "Heritage Walk");
            assertThat(walkCard).containsEntry("photos", 3).containsEntry("videos", 0).containsEntry("pending", 0);
            assertThat(((Map<?, ?>) ((Map<?, ?>) walkCard.get("event")).get("club")).get("name")).isEqualTo("Photo Club");
            assertThat((String) walkCard.get("cover")).contains("/uploads/gallery/");
            assertThat((List<?>) walkCard.get("previews")).isNotEmpty();
            assertThat(card(list, "Draft Event")).isNull();
            assertThat((Integer) list.at("$.meta.counts.all")).isEqualTo(2);
            assertThat((Integer) list.at("$.meta.counts.photos")).isEqualTo(1);
            assertThat((Integer) list.at("$.meta.counts.review")).isZero();
            assertThat((Boolean) list.at("$.meta.canReview")).isFalse();

            // Events with photos are listed first.
            assertThat(list.string("$.data[0].event.title")).isEqualTo("Heritage Walk");
            assertThat((Integer) list.at("$.meta.total")).isEqualTo(2);
            List<String> paged = api.get("/api/gallery?limit=1&page=2").at("$.data[*].event.title");
            assertThat(paged).containsExactly("Other Event");

            List<String> withPhotos = api.get("/api/gallery?show=photos").at("$.data[*].event.title");
            assertThat(withPhotos).containsExactly("Heritage Walk");
            List<String> searched = api.get("/api/gallery?search=other").at("$.data[*].event.title");
            assertThat(searched).containsExactly("Other Event");
            Response invalid = api.get("/api/gallery?show=everything");
            assertThat(invalid.status()).isEqualTo(400);
            assertThat(invalid.message()).isEqualTo("Invalid value");
        }

        @Test
        void thePresidentAndVpSeeWhatsWaitingForThemOthersDont() {
            section();
            // Asha's video was declined, so only the member's new photo waits.
            String video = gallery(president).string("$.data.pending[0]._id");
            api.as(president).post("/api/events/" + walk.getId() + "/gallery/reject", Map.of("ids", List.of(video)));
            add(member);

            Response forVice = api.as(vice).get("/api/gallery?show=review");
            assertThat((Boolean) forVice.at("$.meta.canReview")).isTrue();
            assertThat((Integer) forVice.at("$.meta.counts.review")).isEqualTo(1);
            assertThat((List<?>) forVice.at("$.data")).hasSize(1);
            assertThat((Integer) forVice.at("$.data[0].pending")).isEqualTo(1);
            assertThat(forVice.string("$.data[0].event.title")).isEqualTo("Heritage Walk");

            Response forMember = api.as(member).get("/api/gallery");
            assertThat((Boolean) forMember.at("$.meta.canReview")).isFalse();
            assertThat(card(forMember, "Heritage Walk")).containsEntry("pending", 0);
        }

        @Test
        void theEventPageCarriesHowManyPhotosAreInItsGallery() {
            section();
            Response detail = SocialFixtures.assumeRoute(api.get("/api/events/" + walk.getId()));
            assertThat((Integer) detail.at("$.data.galleryCount")).isEqualTo(3);
        }
    }

    @Nested
    class Deleting {

        @Test
        void uploadersDeleteTheirOwnThePresidentVpCanRemoveAnyonesOthersCant() {
            reviewedState();
            assertThat(add(president).status()).isEqualTo(201);
            List<Map<String, Object>> items = gallery(null).at("$.data.items");
            String memberItem = items.stream().filter(item -> "Plain Member".equals(((Map<?, ?>) item.get("uploader")).get("name"))).map(item -> (String) item.get("_id")).findFirst().orElseThrow();
            String presidentItem = items.stream().filter(item -> "President".equals(((Map<?, ?>) item.get("uploader")).get("name"))).map(item -> (String) item.get("_id")).findFirst().orElseThrow();
            String url = "/api/events/" + walk.getId() + "/gallery/";

            Response bySomeoneElse = api.as(bina).delete(url + memberItem);
            assertThat(bySomeoneElse.status()).isEqualTo(403);
            assertThat(bySomeoneElse.message()).isEqualTo("You can only delete your own photos and videos");
            assertThat(api.as(coordinator).delete(url + memberItem).status()).isEqualTo(403);
            Response own = api.as(member).delete(url + memberItem);
            assertThat(own.status()).isEqualTo(200);
            assertThat(own.message()).isEqualTo("Deleted from the gallery");
            assertThat((Integer) own.at("$.data.counts.approved")).isEqualTo(2);

            assertThat(api.as(vice).delete(url + presidentItem).status()).isEqualTo(200);
            assertThat(mongo.count(Query.query(Criteria.where("action").is("GALLERY_MEDIA_REMOVED")), AuditLog.class)).isEqualTo(2);
            Response gone = api.as(vice).delete(url + presidentItem);
            assertThat(gone.status()).isEqualTo(404);
            assertThat(gone.message()).isEqualTo("Photo or video not found");
        }
    }

    @Nested
    class Limits {

        @Test
        void aPersonCanHaveOnlySoManyUploadsWaitingForReview() {
            int before = properties.getGallery().getMaxPerOwner();
            properties.getGallery().setMaxPerOwner(2);
            try {
                assertThat(add(member).status()).isEqualTo(201);
                assertThat(add(member).status()).isEqualTo(201);
                Response blocked = api.as(member).post("/api/events/" + walk.getId() + "/gallery/uploads", Map.of("kinds", List.of("IMAGE")));
                assertThat(blocked.status()).isEqualTo(409);
                assertThat(blocked.message()).contains("2 uploads waiting for review");
            } finally {
                properties.getGallery().setMaxPerOwner(before);
            }
        }

        @Test
        void theGalleryPagesItsApprovedItems() {
            List<EventMedia> seeded = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                EventMedia item = new EventMedia();
                item.setEvent(walk.getId());
                item.setClub(club.getId());
                item.setUploader(president.id());
                item.setUploaderRole(UploaderRole.MEMBER);
                item.setStatus(GalleryStatus.APPROVED);
                MediaFile media = new MediaFile();
                media.setKind(MediaKind.IMAGE);
                media.setProvider("local");
                media.setKey("gallery/" + walk.getId() + "/seed-" + i + ".png");
                item.setMedia(media);
                seeded.add(item);
            }
            mongo.insertAll(seeded);

            Response first = gallery(null);
            assertThat((List<?>) first.at("$.data.items")).hasSize(24);
            assertThat((Integer) first.at("$.meta.page")).isEqualTo(1);
            assertThat((Integer) first.at("$.meta.totalPages")).isEqualTo(2);
            assertThat((Integer) first.at("$.meta.total")).isEqualTo(30);
            Response second = api.get("/api/events/" + walk.getId() + "/gallery?page=2");
            assertThat((List<?>) second.at("$.data.items")).hasSize(6);
            assertThat(otherClub).isNotNull();
        }
    }

    @Nested
    class BeyondTheOriginalTests {

        @Test
        void likingAPhotoTwiceCountsOnceAndTheGalleryShowsTheViewersLike() {
            reviewedState();
            String photo = gallery(null).string("$.data.items[0]._id");
            AuthUser ashaUser = authUser(asha);
            likes.setLiked(ashaUser, "media", photo, true);
            assertThat(likes.setLiked(ashaUser, "media", photo, true)).containsEntry("likeCount", 1);

            Response forAsha = gallery(asha);
            assertThat((Integer) forAsha.at("$.data.items[0].likeCount")).isEqualTo(1);
            assertThat((Boolean) forAsha.at("$.data.items[0].likedByMe")).isTrue();
            assertThat((Boolean) gallery(bina).at("$.data.items[0].likedByMe")).isFalse();
        }

        @Test
        void requestsAreValidatedWithTheOriginalMessages() {
            String url = "/api/events/" + walk.getId() + "/gallery";
            assertThat(api.as(member).post(url, Map.of()).message()).isEqualTo("Add a photo or video");
            assertThat(api.as(member).post(url + "/uploads", Map.of()).message()).isEqualTo("Choose up to 20 photos or videos at a time");
            assertThat(api.as(president).post(url + "/approve", Map.of("ids", List.of())).message()).isEqualTo("Choose photos or videos");
            assertThat(api.as(president).post(url + "/reject", Map.of("ids", List.of(new ObjectId().toHexString()), "reason", "x".repeat(201))).message())
                    .isEqualTo("Keep the reason under 200 characters");
            assertThat(api.get("/api/events/not-an-id/gallery").message()).isEqualTo("Invalid id");
            assertThat(api.as(member).delete(url + "/not-an-id").message()).isEqualTo("Invalid mediaId");
            assertThat(api.get("/api/events/" + new ObjectId() + "/gallery").message()).isEqualTo("Event not found");
            assertThat(api.get("/api/gallery?club=nope").message()).isEqualTo("Invalid value");
        }

        @Test
        void filesBiggerThanThePhotoLimitAreRefusedWithTheOriginalMessage() {
            long before = properties.getGallery().getMaxImageBytes();
            properties.getGallery().setMaxImageBytes(16);
            try {
                Response refused = uploadFile(member, walk.getId(), PNG, "big.png");
                assertThat(refused.status()).isEqualTo(413);
                assertThat(refused.message()).isEqualTo("Photos must be 0 MB or smaller");
                assertThat(refused.errorCode()).isEqualTo("UPLOAD_ERROR");
            } finally {
                properties.getGallery().setMaxImageBytes(before);
            }
        }
    }

    AuthUser authUser(TestUser user) {
        return AuthUser.of(mongo.findById(user.id(), User.class));
    }
}
