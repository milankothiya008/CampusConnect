package com.campusconnect.feature.story;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.social.Story;
import com.campusconnect.domain.social.StoryView;
import com.campusconnect.feature.story.StoryDtos.CreateStoryBody;
import com.campusconnect.feature.story.StoryDtos.Liked;
import com.campusconnect.feature.story.StoryDtos.LocalMedia;
import com.campusconnect.feature.story.StoryDtos.StoryCard;
import com.campusconnect.feature.story.StoryDtos.StoryViewer;
import com.campusconnect.feature.story.StoryDtos.TrayGroup;
import com.campusconnect.feature.story.StoryDtos.Viewers;
import com.campusconnect.feature.story.StoryDtos.ViewersMeta;
import com.campusconnect.feature.story.StoryTray.LiveStory;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Club stories (services/StoryService.js): photos and short videos that disappear after a day, shown as rings at
 * the top of the feed. Anyone signed in can watch them; the club's officers who can post updates (president,
 * vice-president, marketing coordinator) post them and see who watched. The files live in media storage
 * ({@link MediaStores#story()}); the database only describes them.
 */
@Slf4j
@Service
public class StoryService {

    static final ClubPermission MANAGE = ClubPermission.POST_UPDATES;
    static final int SWEEP_BATCH = 100;
    static final int MAX_CLEANUP_ATTEMPTS = 5;

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final AuditService audit;
    private final Refs refs;
    private final StoryTray trays;
    private final MediaStore media;
    private final AppProperties properties;

    public StoryService(MongoOperations mongo, ClubAccess access, AuditService audit, Refs refs, StoryTray trays, MediaStores mediaStores, AppProperties properties) {
        this.mongo = mongo;
        this.access = access;
        this.audit = audit;
        this.refs = refs;
        this.trays = trays;
        this.media = mediaStores.story();
        this.properties = properties;
    }

    private static AppException notFound() {
        return AppException.notFound("This story is no longer available");
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(new ObjectId(id)));
    }

    // ---------------------------------------------------------------- the tray

    public List<TrayGroup> getTray(AuthUser user) {
        StoryTray.Tray tray = trays.current();
        List<ObjectId> storyIds = tray.groups().stream().flatMap(group -> group.stories().stream()).map(story -> new ObjectId(story.id())).toList();

        Map<String, Boolean> viewed = new HashMap<>();
        if (!storyIds.isEmpty()) {
            Query views = Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("story").in(storyIds));
            views.fields().include("story").include("liked");
            mongo.find(views, Document.class, "storyviews").forEach(view -> viewed.put(view.getObjectId("story").toHexString(), Boolean.TRUE.equals(view.getBoolean("liked"))));
        }
        Set<String> managed = new HashSet<>(access.clubIdsWithAnyPermission(user.id(), List.of(MANAGE)));
        Map<String, int[]> counts = liveCounts(tray, managed);

        List<TrayGroup> result = new ArrayList<>(tray.groups().stream().map(group -> {
            boolean canManage = managed.contains(String.valueOf(group.club().get("_id")));
            List<StoryCard> stories = group.stories().stream()
                    .map(story -> card(story, viewed.containsKey(story.id()), Boolean.TRUE.equals(viewed.get(story.id())),
                            canManage ? counts.getOrDefault(story.id(), new int[] {0, 0}) : null, null))
                    .toList();
            return new TrayGroup(group.club(), canManage, group.latestAt(), stories.stream().allMatch(StoryCard::seen), stories);
        }).toList());

        // The viewer's own clubs first (like "Your story"), then unwatched rings, newest first.
        result.sort(Comparator.comparing((TrayGroup group) -> !group.canManage())
                .thenComparing(TrayGroup::allSeen)
                .thenComparing(TrayGroup::latestAt, Comparator.reverseOrder()));
        return result;
    }

    /** Fresh view and like counts for the stories of clubs the viewer manages (the cached tray may be older). */
    private Map<String, int[]> liveCounts(StoryTray.Tray tray, Set<String> managed) {
        List<ObjectId> ids = tray.groups().stream()
                .filter(group -> managed.contains(String.valueOf(group.club().get("_id"))))
                .flatMap(group -> group.stories().stream())
                .map(story -> new ObjectId(story.id()))
                .toList();
        Map<String, int[]> counts = new HashMap<>();
        if (ids.isEmpty()) {
            return counts;
        }
        Query query = Query.query(Criteria.where("_id").in(ids));
        query.fields().include("viewCount").include("likeCount");
        mongo.find(query, Document.class, "stories").forEach(row -> counts.put(row.getObjectId("_id").toHexString(), new int[] {intOf(row.get("viewCount")), intOf(row.get("likeCount"))}));
        return counts;
    }

    /** What one viewer sees of a story; {@code counts} (views, likes) only for the club's story managers. */
    private static StoryCard card(LiveStory story, boolean seen, boolean liked, int[] counts, Map<String, Object> club) {
        return new StoryCard(story.id(), story.kind(), story.urls().url(), story.urls().poster(), story.urls().thumb(), story.width(), story.height(),
                story.duration(), story.caption(), story.event(), story.createdAt(), story.expiresAt(), seen, liked,
                counts == null ? null : counts[0], counts == null ? null : counts[1], club);
    }

    // ---------------------------------------------------------------- posting

    private ClubContext assertCanPost(AuthUser actor, String clubId) {
        ClubContext context = access.assertPermission(actor, clubId, MANAGE, "Only club officers who post updates can add stories for this club");
        if (context.club().getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("Only active clubs can post stories", ErrorCode.CLUB_NOT_ACTIVE);
        }
        int max = properties.getStories().getMaxPerOwner();
        long active = mongo.count(Query.query(Criteria.where("club").is(new ObjectId(context.club().getId())).and("expiresAt").gt(Instant.now())), Story.class);
        if (active >= max) {
            throw AppException.conflict("A club can have up to " + max + " live stories. Delete one or wait for older ones to expire.", ErrorCode.CONFLICT);
        }
        return context;
    }

    public MediaStore.UploadTicket createUploadTicket(AuthUser actor, String clubId, String kind) {
        if (!"IMAGE".equals(kind) && !"VIDEO".equals(kind)) {
            throw AppException.badRequest("Choose a photo or a video", ErrorCode.VALIDATION_ERROR);
        }
        Club club = assertCanPost(actor, clubId).club();
        return media.createUploadTicket(club.getId(), actor.id(), MediaKind.valueOf(kind), null);
    }

    /**
     * Development storage only: with Cloudinary configured the browser uploads straight to Cloudinary. The size limit
     * is checked first, before anything else, like the upload middleware did.
     */
    public LocalMedia uploadLocalMedia(AuthUser actor, String clubId, MultipartFile file) {
        AppProperties.MediaLimits limits = properties.getStories();
        if (file != null && file.getSize() > Math.max(limits.getMaxImageBytes(), limits.getMaxVideoBytes())) {
            throw AppException.of(413, "Story files must be " + Math.round(limits.getMaxVideoBytes() / (1024d * 1024d)) + " MB or smaller", ErrorCode.UPLOAD_ERROR);
        }
        if (!"local".equals(media.providerName())) {
            throw AppException.badRequest("Story media is uploaded directly to cloud storage", ErrorCode.UPLOAD_ERROR);
        }
        Club club = assertCanPost(actor, clubId).club();
        MediaStore.LocalUpload saved = media.saveLocalFile(bytesOf(file), file == null ? null : file.getOriginalFilename(), club.getId(), actor.id(), null, null);
        return new LocalMedia("local", saved.token(), saved.kind(), saved.bytes(), saved.format());
    }

    private static byte[] bytesOf(MultipartFile file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw AppException.badRequest("Invalid upload", ErrorCode.UPLOAD_ERROR);
        }
    }

    private Document findClubEvent(String eventId, String clubId) {
        Query query = byId(eventId);
        query.fields().include("club").include("title").include("poster").include("status").include("startAt").include("endAt");
        Document event = mongo.findOne(query, Document.class, "events");
        boolean published = event != null && EventStatus.PUBLIC.stream().anyMatch(status -> status.name().equals(event.getString("status")));
        if (!published || event.getObjectId("club") == null || !event.getObjectId("club").toHexString().equals(clubId)) {
            throw AppException.badRequest("Link a published event of this club", ErrorCode.VALIDATION_ERROR);
        }
        return event;
    }

    public StoryCard createStory(AuthUser actor, CreateStoryBody payload) {
        Club club = assertCanPost(actor, payload.club()).club();
        Document event = payload.event() == null || payload.event().isEmpty() ? null : findClubEvent(payload.event(), club.getId());

        MediaFile file;
        if ("event".equals(payload.media().get("provider"))) {
            // Sharing an event's poster: reuses the file the event already has, nothing new is uploaded.
            String poster = event == null ? null : event.getString("poster");
            if (poster == null || poster.isEmpty()) {
                throw AppException.badRequest("That event has no poster to share", ErrorCode.VALIDATION_ERROR);
            }
            file = new MediaFile();
            file.setKind(MediaKind.IMAGE);
            file.setProvider("event");
            file.setKey(poster);
        } else {
            file = media.verifyUpload(payload.media(), club.getId(), actor.id()).toMediaFile();
        }

        String caption = payload.caption() == null ? "" : payload.caption().strip();
        Story story = new Story();
        story.setClub(club.getId());
        story.setAuthor(actor.id());
        story.setMedia(file);
        story.setCaption(caption.length() > 200 ? caption.substring(0, 200) : caption);
        story.setEvent(event == null ? null : event.getObjectId("_id").toHexString());
        story.setExpiresAt(Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofHours(properties.getStories().getLifetimeHours())));
        try {
            story = mongo.insert(story);
        } catch (RuntimeException e) {
            media.deleteMediaQuietly(file);
            throw e;
        }

        trays.invalidate();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", club.getId());
        metadata.put("kind", file.getKind().name());
        metadata.put("provider", file.getProvider());
        audit.record(AuditAction.STORY_POSTED, actor.id(), "Story", story.getId(), metadata);

        Map<String, Object> shownClub = new LinkedHashMap<>();
        shownClub.put("_id", club.getId());
        shownClub.put("name", club.getName());
        shownClub.put("logo", club.getLogo());
        return card(trays.live(story, event == null ? null : Refs.shape(event, "title", "startAt", "endAt", "status")), false, false, new int[] {0, 0}, shownClub);
    }

    // ---------------------------------------------------------------- watching

    private Story findLiveStory(String storyId) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(storyId)).and("expiresAt").gt(Instant.now()));
        query.fields().include("club").include("author").include("viewCount").include("likeCount");
        Story story = mongo.findOne(query, Story.class);
        if (story == null) {
            throw notFound();
        }
        return story;
    }

    private static boolean isAuthor(Story story, AuthUser user) {
        return user.id().equals(story.getAuthor());
    }

    /**
     * Records that the person watched the story. The author's own views are kept (so their ring shows as seen) but
     * never counted or listed.
     */
    public Story recordView(AuthUser user, String storyId) {
        Story story = findLiveStory(storyId);
        boolean inserted = false;
        try {
            var result = mongo.upsert(
                    Query.query(Criteria.where("story").is(new ObjectId(story.getId())).and("user").is(new ObjectId(user.id()))),
                    new Update().setOnInsert("viewedAt", Instant.now()).setOnInsert("liked", false).setOnInsert("likedAt", null),
                    StoryView.class);
            inserted = result.getUpsertedId() != null;
        } catch (DuplicateKeyException e) {
            // Two tabs recording the same view at once: the other one won, which is fine.
            inserted = false;
        }
        if (inserted && !isAuthor(story, user)) {
            mongo.updateFirst(byId(story.getId()), new Update().inc("viewCount", 1), Story.class);
        }
        return story;
    }

    /** Likes or unlikes; the count only moves when the like really changes, so a double tap counts once. */
    public Liked setLiked(AuthUser user, String storyId, boolean liked) {
        Story story = recordView(user, storyId);
        if (isAuthor(story, user)) {
            throw AppException.badRequest("You can't like your own club's story", ErrorCode.VALIDATION_ERROR);
        }
        var changed = mongo.updateFirst(
                Query.query(Criteria.where("story").is(new ObjectId(story.getId())).and("user").is(new ObjectId(user.id())).and("liked").ne(liked)),
                new Update().set("liked", liked).set("likedAt", liked ? Instant.now() : null),
                StoryView.class);
        if (changed.getModifiedCount() > 0) {
            Criteria target = Criteria.where("_id").is(new ObjectId(story.getId()));
            if (!liked) {
                target = target.and("likeCount").gt(0);
            }
            mongo.updateFirst(Query.query(target), new Update().inc("likeCount", liked ? 1 : -1), Story.class);
        }
        return new Liked(liked);
    }

    public Viewers listViewers(AuthUser actor, String storyId, String page, String limit) {
        Story story = findLiveStory(storyId);
        access.assertPermission(actor, story.getClub(), MANAGE, "Only the club's story managers can see who viewed this story");

        Pagination pagination = Pagination.of(page, limit, 50, 100);
        Criteria filter = Criteria.where("story").is(new ObjectId(story.getId())).and("user").ne(new ObjectId(story.getAuthor()));
        List<StoryView> views = mongo.find(Query.query(filter).with(Sort.by(Sort.Direction.DESC, "viewedAt")).skip(pagination.skip()).limit(pagination.limit()), StoryView.class);
        long total = mongo.count(Query.query(filter), StoryView.class);
        Map<String, Map<String, Object>> users = refs.users(views.stream().map(StoryView::getUser).toList(), "name", "departmentCode", "batchCode", "accountType", "avatar");

        List<StoryViewer> items = views.stream()
                .filter(view -> users.containsKey(view.getUser()))
                .map(view -> new StoryViewer(users.get(view.getUser()), view.getViewedAt(), view.isLiked()))
                .toList();
        var meta = pagination.meta(total);
        return new Viewers(items, new ViewersMeta(meta.total(), meta.page(), meta.limit(), meta.totalPages(), story.getViewCount(), story.getLikeCount()));
    }

    // ---------------------------------------------------------------- removal and expiry

    private void removeStoryRecord(String storyId) {
        mongo.remove(Query.query(Criteria.where("story").is(new ObjectId(storyId))), StoryView.class);
        mongo.remove(byId(storyId), Story.class);
    }

    public void deleteStory(AuthUser actor, String storyId) {
        Story story = mongo.findById(new ObjectId(storyId), Story.class);
        if (story == null) {
            throw notFound();
        }
        access.assertPermission(actor, story.getClub(), MANAGE, "Only the club's story managers can delete this story");

        if (media.deleteMediaQuietly(story.getMedia())) {
            removeStoryRecord(story.getId());
        } else {
            // Hidden at once; the sweeper retries removing the file from storage.
            mongo.updateFirst(byId(story.getId()), new Update().set("expiresAt", Instant.now()), Story.class);
        }
        trays.invalidate();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", story.getClub());
        audit.record(AuditAction.STORY_DELETED, actor.id(), "Story", story.getId(), metadata);
    }

    /** What one sweep did: stories removed, and stories whose file could not be removed yet. */
    public record SweepResult(int removed, int pending) {
    }

    /** Deletes expired stories: first the file in storage, then the story and its views. */
    public SweepResult sweepExpiredStories(Instant now) {
        List<Story> expired = mongo.find(Query.query(Criteria.where("expiresAt").lte(now)).limit(SWEEP_BATCH), Story.class);
        int removed = 0;
        for (Story story : expired) {
            boolean cleaned = media.deleteMediaQuietly(story.getMedia());
            if (cleaned || story.getCleanupAttempts() + 1 >= MAX_CLEANUP_ATTEMPTS) {
                if (!cleaned) {
                    log.error("Giving up on story media cleanup: story {} ({})", story.getId(), story.getMedia() == null ? null : story.getMedia().getKey());
                }
                removeStoryRecord(story.getId());
                removed += 1;
            } else {
                mongo.updateFirst(byId(story.getId()), new Update().inc("cleanupAttempts", 1), Story.class);
            }
        }
        if (!expired.isEmpty()) {
            trays.invalidate();
        }
        return new SweepResult(removed, expired.size() - removed);
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
