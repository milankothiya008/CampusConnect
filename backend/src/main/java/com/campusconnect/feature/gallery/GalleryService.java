package com.campusconnect.feature.gallery;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.PageMeta;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventMedia;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.UploaderRole;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.social.LikeTargetType;
import com.campusconnect.feature.gallery.GalleryDtos.Access;
import com.campusconnect.feature.gallery.GalleryDtos.Approved;
import com.campusconnect.feature.gallery.GalleryDtos.Counts;
import com.campusconnect.feature.gallery.GalleryDtos.Gallery;
import com.campusconnect.feature.gallery.GalleryDtos.GalleryItem;
import com.campusconnect.feature.gallery.GalleryDtos.Limits;
import com.campusconnect.feature.gallery.GalleryDtos.LocalMedia;
import com.campusconnect.feature.gallery.GalleryDtos.Rejected;
import com.campusconnect.feature.gallery.GalleryDtos.Viewer;
import com.campusconnect.feature.like.LikeService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Event galleries (services/GalleryService.js). Club members can add photos and videos once the event is
 * published; participants once the event has started and they have been checked in at the door. Nothing is public
 * until the club's president or vice-president approves it (their own uploads go straight in). Rejected uploads
 * are deleted. The files live in media storage ({@link MediaStores#gallery()}), issued per event.
 */
@Service
public class GalleryService {

    static final ClubPermission MODERATE = ClubPermission.MODERATE_GALLERY;
    static final int MAX_TICKETS = 20;
    static final int MAX_REVIEW_BATCH = 100;
    /** One "new uploads to review" notification per uploader per burst of uploads, not one per file. */
    static final Duration SUBMIT_NOTICE_QUIET = Duration.ofMinutes(15);
    static final String NOT_ALLOWED = "Only club members and checked-in participants can add photos and videos to this event.";
    private static final String UNKNOWN_UPLOADER = "CampusConnect user";

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final LikeService likes;
    private final Refs refs;
    private final GalleryActivity activity;
    private final MediaStore media;
    private final AppProperties properties;

    public GalleryService(MongoOperations mongo, ClubAccess access, AuditService audit, NotificationService notifications, LikeService likes, Refs refs,
            GalleryActivity activity, MediaStores mediaStores, AppProperties properties) {
        this.mongo = mongo;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.likes = likes;
        this.refs = refs;
        this.activity = activity;
        this.media = mediaStores.gallery();
        this.properties = properties;
    }

    /** An event's gallery and its pagination meta. */
    public record GalleryPage(Gallery gallery, PageMeta meta) {
    }

    private record Upload(Event event, Access access) {
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(new ObjectId(id)));
    }

    private static String plural(long count, String word) {
        return count + " " + word + (count == 1 ? "" : "s");
    }

    private static String itemsLabel(List<EventMedia> items) {
        long videos = items.stream().filter(item -> item.getMedia().getKind() == MediaKind.VIDEO).count();
        long photos = items.size() - videos;
        List<String> parts = new ArrayList<>();
        if (photos > 0) {
            parts.add(plural(photos, "photo"));
        }
        if (videos > 0) {
            parts.add(plural(videos, "video"));
        }
        return String.join(" and ", parts);
    }

    private Event findEvent(String eventId) {
        Query query = byId(eventId);
        query.fields().include("title").include("club").include("status").include("startAt").include("endAt");
        Event event = mongo.findOne(query, Event.class);
        if (event == null) {
            throw AppException.notFound("Event not found");
        }
        return event;
    }

    private static boolean isPublic(Event event) {
        return EventStatus.PUBLIC.contains(event.getStatus());
    }

    // ---------------------------------------------------------------- who may do what

    /** What the signed-in user (or an anonymous visitor) may do with this event's gallery. */
    Access accessOf(AuthUser actor, Event event) {
        if (actor == null) {
            return new Access(false, false, null, null, NOT_ALLOWED);
        }
        ClubContext context = access.context(actor, event.getClub());
        boolean canModerate = context.has(MODERATE);
        if (!isPublic(event)) {
            return new Access(false, canModerate, null, null, "Photos can be added once the event is published.");
        }
        if (context.isMember()) {
            return new Access(true, canModerate, UploaderRole.MEMBER.name(), null, null);
        }

        Query query = Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").is(new ObjectId(actor.id()))
                .and("status").is(RegistrationStatus.REGISTERED.name()));
        query.fields().include("checkedInAt");
        EventRegistration registration = mongo.findOne(query, EventRegistration.class);
        Instant now = Instant.now();
        boolean started = event.getStartAt() == null || !event.getStartAt().isAfter(now);
        if (registration != null && registration.getCheckedInAt() != null && started) {
            return new Access(true, canModerate, UploaderRole.PARTICIPANT.name(), null, null);
        }

        boolean ended = event.getStatus() == EventStatus.COMPLETED || event.getEndAt() == null || !event.getEndAt().isAfter(now);
        String hint = null;
        if (registration != null && !started) {
            hint = "You can add photos and videos once the event starts and you've been checked in.";
        } else if (registration != null && !ended && registration.getCheckedInAt() == null) {
            hint = "Get checked in at the entrance to add your photos and videos.";
        }
        return new Access(false, canModerate, null, hint, hint != null ? hint : NOT_ALLOWED);
    }

    private Limits limits() {
        AppProperties.MediaLimits gallery = properties.getGallery();
        return new Limits(gallery.getMaxImageBytes(), gallery.getMaxVideoBytes(), gallery.getMaxVideoSeconds(), gallery.getMaxPerOwner());
    }

    private Viewer viewer(Access granted) {
        return new Viewer(granted.canUpload(), granted.canModerate(), granted.role(), granted.hint(), granted.reason(), limits());
    }

    private Upload assertCanUpload(AuthUser actor, String eventId) {
        Event event = findEvent(eventId);
        Access granted = accessOf(actor, event);
        if (!granted.canUpload()) {
            throw AppException.forbidden(granted.reason());
        }
        return new Upload(event, granted);
    }

    /** Uploads waiting for review are capped per person and event, so the review queue can't be flooded. */
    private void assertPendingRoom(AuthUser actor, Event event, Access granted, int adding) {
        if (granted.canModerate()) {
            return;
        }
        long waiting = mongo.count(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("uploader").is(new ObjectId(actor.id()))
                .and("status").is(GalleryStatus.PENDING.name())), EventMedia.class);
        int max = properties.getGallery().getMaxPerOwner();
        if (waiting + adding > max) {
            throw AppException.conflict("You have " + plural(waiting, "upload") + " waiting for review. You can add more once the club has reviewed them (up to "
                    + max + " at a time).", ErrorCode.CONFLICT);
        }
    }

    // ---------------------------------------------------------------- views

    private GalleryItem itemView(EventMedia item, Map<String, Object> uploader, String viewerId, boolean canModerate, boolean likedByMe) {
        Map<String, Object> shown = new LinkedHashMap<>();
        Object uploaderId = uploader == null ? null : uploader.get("_id");
        Object name = uploader == null ? null : uploader.get("name");
        shown.put("_id", uploaderId);
        shown.put("name", name == null || "".equals(name) ? UNKNOWN_UPLOADER : name);
        boolean mine = viewerId != null && viewerId.equals(uploaderId);
        MediaFile file = item.getMedia();
        MediaStore.MediaUrls urls = media.mediaUrls(file);
        return new GalleryItem(item.getId(), file.getKind(), urls.url(), urls.poster(), urls.thumb(), file.getWidth(), file.getHeight(), file.getDuration(),
                item.getStatus(), shown, item.getUploaderRole(), item.getCreatedAt(), item.getLikeCount(), likedByMe, mine, mine || canModerate);
    }

    /**
     * The gallery as the viewer sees it: approved items (paged, newest first), the viewer's own uploads still waiting
     * for review, and — for the president and vice-president — the whole review queue.
     */
    public GalleryPage getGallery(AuthUser actor, String eventId, String page, String limit) {
        Event event = findEvent(eventId);
        Access granted = accessOf(actor, event);
        Pagination pagination = Pagination.of(page, limit, 24, 60);
        if (!isPublic(event)) {
            return new GalleryPage(new Gallery(List.of(), List.of(), List.of(), new Counts(0, 0), viewer(granted)), pagination.meta(0));
        }

        ObjectId eventOid = new ObjectId(event.getId());
        boolean canModerate = granted.canModerate();
        Criteria approved = Criteria.where("event").is(eventOid).and("status").is(GalleryStatus.APPROVED.name());
        List<EventMedia> items = mongo.find(Query.query(approved)
                .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "_id")))
                .skip(pagination.skip()).limit(pagination.limit()), EventMedia.class);
        long approvedCount = mongo.count(Query.query(approved), EventMedia.class);

        Criteria waiting = Criteria.where("event").is(eventOid).and("status").is(GalleryStatus.PENDING.name());
        List<EventMedia> mine = actor != null && !canModerate
                ? mongo.find(Query.query(Criteria.where("event").is(eventOid).and("uploader").is(new ObjectId(actor.id())).and("status").is(GalleryStatus.PENDING.name()))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt")), EventMedia.class)
                : List.of();
        List<EventMedia> pending = canModerate
                ? mongo.find(Query.query(waiting).with(Sort.by(Sort.Direction.ASC, "createdAt")).limit(MAX_REVIEW_BATCH), EventMedia.class)
                : List.of();
        long pendingCount = canModerate ? mongo.count(Query.query(waiting), EventMedia.class) : 0;

        // One lookup for every uploader on the page, and one for what the viewer has liked.
        Map<String, Map<String, Object>> uploaders = refs.users(Stream.of(items, mine, pending).flatMap(List::stream).map(EventMedia::getUploader)
                .filter(Objects::nonNull).distinct().toList(), "name");
        Set<String> liked = likes.likedAmong(actor, LikeTargetType.MEDIA, items.stream().map(EventMedia::getId).toList());
        String viewerId = actor == null ? null : actor.id();

        return new GalleryPage(new Gallery(
                items.stream().map(item -> itemView(item, uploaders.get(item.getUploader()), viewerId, canModerate, liked.contains(item.getId()))).toList(),
                mine.stream().map(item -> itemView(item, uploaders.get(item.getUploader()), viewerId, canModerate, false)).toList(),
                pending.stream().map(item -> itemView(item, uploaders.get(item.getUploader()), viewerId, canModerate, false)).toList(),
                new Counts(approvedCount, canModerate ? pendingCount : mine.size()),
                viewer(granted)), pagination.meta(approvedCount));
    }

    // ---------------------------------------------------------------- uploading

    /** Upload tickets for a batch of files (one request, so picking 20 photos doesn't hit the upload rate limit). */
    public List<MediaStore.UploadTicket> createUploadTickets(AuthUser actor, String eventId, List<String> kinds) {
        List<String> list = kinds == null ? List.of() : kinds.stream().limit(MAX_TICKETS).toList();
        if (list.isEmpty() || list.stream().anyMatch(kind -> !"IMAGE".equals(kind) && !"VIDEO".equals(kind))) {
            throw AppException.badRequest("Choose photos or videos", ErrorCode.VALIDATION_ERROR);
        }
        Upload upload = assertCanUpload(actor, eventId);
        assertPendingRoom(actor, upload.event(), upload.access(), list.size());
        return list.stream().map(kind -> media.createUploadTicket(upload.event().getId(), actor.id(), MediaKind.valueOf(kind), null)).toList();
    }

    /**
     * Development storage only: with Cloudinary configured the browser uploads straight to Cloudinary. The size limit
     * is checked first, before anything else, like the upload middleware did.
     */
    public LocalMedia uploadLocalMedia(AuthUser actor, String eventId, MultipartFile file) {
        AppProperties.MediaLimits limits = properties.getGallery();
        if (file != null && file.getSize() > Math.max(limits.getMaxImageBytes(), limits.getMaxVideoBytes())) {
            throw AppException.of(413, "Gallery files must be " + Math.round(limits.getMaxVideoBytes() / (1024d * 1024d)) + " MB or smaller", ErrorCode.UPLOAD_ERROR);
        }
        if (!"local".equals(media.providerName())) {
            throw AppException.badRequest("Gallery media is uploaded directly to cloud storage", ErrorCode.UPLOAD_ERROR);
        }
        Event event = assertCanUpload(actor, eventId).event();
        MediaStore.LocalUpload saved = media.saveLocalFile(bytesOf(file), file == null ? null : file.getOriginalFilename(), event.getId(), actor.id(), null, null);
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

    /** Attaches an uploaded file to the gallery: straight in for the president/VP, otherwise waiting for review. */
    public GalleryItem addMedia(AuthUser actor, String eventId, Map<String, Object> uploaded) {
        Upload upload = assertCanUpload(actor, eventId);
        Event event = upload.event();
        boolean canModerate = upload.access().canModerate();
        MediaFile file = media.verifyUpload(uploaded, event.getId(), actor.id()).toMediaFile();
        try {
            assertPendingRoom(actor, event, upload.access(), 1);
        } catch (AppException e) {
            media.deleteMediaQuietly(file);
            throw e;
        }

        Instant now = Instant.now();
        EventMedia item = new EventMedia();
        item.setEvent(event.getId());
        item.setClub(event.getClub());
        item.setUploader(actor.id());
        item.setUploaderRole(UploaderRole.valueOf(upload.access().role()));
        item.setMedia(file);
        item.setStatus(canModerate ? GalleryStatus.APPROVED : GalleryStatus.PENDING);
        item.setReviewedBy(canModerate ? actor.id() : null);
        item.setReviewedAt(canModerate ? now : null);
        try {
            item = mongo.insert(item);
        } catch (RuntimeException e) {
            media.deleteMediaQuietly(file);
            throw e;
        }
        if (item.getStatus() == GalleryStatus.APPROVED) {
            activity.changed();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.getId());
        metadata.put("clubId", event.getClub());
        metadata.put("kind", file.getKind().name());
        metadata.put("role", upload.access().role());
        audit.record(AuditAction.GALLERY_MEDIA_ADDED, actor.id(), "EventMedia", item.getId(), null, item.getStatus().name(), null, metadata);
        if (item.getStatus() == GalleryStatus.PENDING) {
            notifyReviewers(actor, event, item);
        }

        Map<String, Object> uploader = new LinkedHashMap<>();
        uploader.put("_id", actor.id());
        uploader.put("name", actor.name());
        return itemView(item, uploader, actor.id(), canModerate, false);
    }

    private void notifyReviewers(AuthUser actor, Event event, EventMedia item) {
        long recent = mongo.count(Query.query(Criteria.where("_id").ne(new ObjectId(item.getId()))
                .and("event").is(new ObjectId(event.getId()))
                .and("uploader").is(new ObjectId(actor.id()))
                .and("status").is(GalleryStatus.PENDING.name())
                .and("createdAt").gte(Instant.now().minus(SUBMIT_NOTICE_QUIET))), EventMedia.class);
        if (recent > 0) {
            return;
        }
        notifications.notify(access.usersWithPermission(event.getClub(), MODERATE), Notice.builder()
                .type(NotificationType.GALLERY_SUBMITTED)
                .title("New photos to review for " + event.getTitle())
                .message(actor.name() + " added to the event gallery. Approve them to show them on the event page.")
                .link("/gallery/" + event.getId() + "?review=1")
                .exclude(List.of(actor.id()))
                .build());
    }

    // ---------------------------------------------------------------- review

    private Counts counts(Event event) {
        ObjectId eventOid = new ObjectId(event.getId());
        return new Counts(
                mongo.count(Query.query(Criteria.where("event").is(eventOid).and("status").is(GalleryStatus.APPROVED.name())), EventMedia.class),
                mongo.count(Query.query(Criteria.where("event").is(eventOid).and("status").is(GalleryStatus.PENDING.name())), EventMedia.class));
    }

    private static Map<String, List<EventMedia>> byUploader(List<EventMedia> items) {
        Map<String, List<EventMedia>> groups = new LinkedHashMap<>();
        items.forEach(item -> groups.computeIfAbsent(item.getUploader(), key -> new ArrayList<>()).add(item));
        return groups;
    }

    private List<EventMedia> loadReviewBatch(AuthUser actor, Event event, List<String> ids) {
        ClubContext context = access.context(actor, event.getClub());
        if (!context.has(MODERATE)) {
            throw AppException.forbidden("Only the club president or vice-president can review gallery uploads");
        }
        List<ObjectId> unique = new LinkedHashSet<>(ids == null ? List.<String>of() : ids).stream().limit(MAX_REVIEW_BATCH).map(ObjectId::new).toList();
        return mongo.find(Query.query(Criteria.where("_id").in(unique).and("event").is(new ObjectId(event.getId())).and("status").is(GalleryStatus.PENDING.name())), EventMedia.class);
    }

    private static Map<String, Object> reviewMetadata(Event event, List<EventMedia> items) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", event.getClub());
        metadata.put("count", items.size());
        metadata.put("mediaIds", items.stream().map(EventMedia::getId).toList());
        return metadata;
    }

    private static List<ObjectId> idsOf(List<EventMedia> items) {
        return items.stream().map(item -> new ObjectId(item.getId())).toList();
    }

    /** President/VP: makes pending uploads public on the event page. */
    public Approved approveMedia(AuthUser actor, String eventId, List<String> ids) {
        Event event = findEvent(eventId);
        List<EventMedia> items = loadReviewBatch(actor, event, ids);
        if (!items.isEmpty()) {
            Instant now = Instant.now();
            mongo.updateMulti(Query.query(Criteria.where("_id").in(idsOf(items)).and("status").is(GalleryStatus.PENDING.name())),
                    new Update().set("status", GalleryStatus.APPROVED.name()).set("reviewedBy", new ObjectId(actor.id())).set("reviewedAt", now).set("updatedAt", now),
                    EventMedia.class);
            activity.changed();
            audit.record(AuditAction.GALLERY_MEDIA_APPROVED, actor.id(), "Event", event.getId(), GalleryStatus.PENDING.name(), GalleryStatus.APPROVED.name(), null,
                    reviewMetadata(event, items));
            byUploader(items).forEach((uploaderId, theirs) -> notifications.notify(uploaderId, Notice.builder()
                    .type(NotificationType.GALLERY_APPROVED)
                    .title("Your " + (theirs.size() == 1 ? (theirs.get(0).getMedia().getKind() == MediaKind.VIDEO ? "video is" : "photo is") : "uploads are")
                            + " in the " + event.getTitle() + " gallery")
                    .message(itemsLabel(theirs) + " you added " + (theirs.size() == 1 ? "is" : "are") + " now on the event page.")
                    .link("/gallery/" + event.getId())
                    .exclude(List.of(actor.id()))
                    .build()));
        }
        return new Approved(items.size(), counts(event));
    }

    /** President/VP: declines pending uploads. The files are deleted and the uploaders told why. */
    public Rejected rejectMedia(AuthUser actor, String eventId, List<String> ids, String reason) {
        Event event = findEvent(eventId);
        List<EventMedia> items = loadReviewBatch(actor, event, ids);
        String trimmed = reason == null ? "" : reason.strip();
        String note = trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
        if (!items.isEmpty()) {
            mongo.remove(Query.query(Criteria.where("_id").in(idsOf(items)).and("status").is(GalleryStatus.PENDING.name())), EventMedia.class);
            items.forEach(item -> media.deleteMediaQuietly(item.getMedia()));
            audit.record(AuditAction.GALLERY_MEDIA_REJECTED, actor.id(), "Event", event.getId(), GalleryStatus.PENDING.name(), null, note.isEmpty() ? null : note,
                    reviewMetadata(event, items));
            byUploader(items).forEach((uploaderId, theirs) -> notifications.notify(uploaderId, Notice.builder()
                    .type(NotificationType.GALLERY_REJECTED)
                    .title("Not added to the " + event.getTitle() + " gallery")
                    .message("The club didn't add " + itemsLabel(theirs) + " you uploaded." + (note.isEmpty() ? "" : " Reason: " + note))
                    .link("/gallery/" + event.getId())
                    .exclude(List.of(actor.id()))
                    .build()));
        }
        return new Rejected(items.size(), counts(event));
    }

    /** Uploaders can delete their own photos and videos; the president and vice-president can remove any. */
    public Counts removeMedia(AuthUser actor, String eventId, String mediaId) {
        Event event = findEvent(eventId);
        EventMedia item = mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(mediaId)).and("event").is(new ObjectId(event.getId()))), EventMedia.class);
        if (item == null) {
            throw AppException.notFound("Photo or video not found");
        }
        boolean mine = actor.id().equals(item.getUploader());
        if (!mine && !access.context(actor, event.getClub()).has(MODERATE)) {
            throw AppException.forbidden("You can only delete your own photos and videos");
        }

        mongo.remove(byId(item.getId()), EventMedia.class);
        if (item.getStatus() == GalleryStatus.APPROVED) {
            activity.changed();
        }
        media.deleteMediaQuietly(item.getMedia());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.getId());
        metadata.put("clubId", event.getClub());
        metadata.put("uploader", item.getUploader());
        metadata.put("kind", item.getMedia().getKind() == null ? null : item.getMedia().getKind().name());
        audit.record(AuditAction.GALLERY_MEDIA_REMOVED, actor.id(), "EventMedia", item.getId(), item.getStatus().name(), null, null, metadata);
        return counts(event);
    }
}
