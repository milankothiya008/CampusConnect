package com.campusconnect.feature.feed;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Broadcast;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Page;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.social.FeedAudience;
import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.domain.social.FeedPostType;
import com.campusconnect.domain.social.FeedVisibility;
import com.campusconnect.feature.audience.AudienceService;
import com.campusconnect.feature.audience.AudienceService.Audience;
import com.campusconnect.feature.audience.AudienceService.Resolved;
import com.campusconnect.feature.audience.CampusMailer;
import com.campusconnect.feature.feed.FeedDtos.AudiencePreview;
import com.campusconnect.feature.feed.FeedDtos.AudiencePreviewBody;
import com.campusconnect.feature.feed.FeedDtos.CreatePostBody;
import com.campusconnect.feature.feed.FeedDtos.FeedPostView;
import com.campusconnect.feature.feed.FeedDtos.FeedQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Club announcements and the posts the system writes about events (services/FeedService.js). The club picks who an
 * announcement goes to (AudienceService): only that audience is notified, e-mailed and — for members-only and
 * chosen-audience posts — able to see it, together with the club's posting team and mentor.
 */
@Service
public class FeedService {

    static final List<FeedPostType> MANUAL_POST_TYPES = List.of(FeedPostType.ANNOUNCEMENT, FeedPostType.CLUB_UPDATE, FeedPostType.EVENT_UPDATE);
    private static final Set<String> TYPE_NAMES = Arrays.stream(FeedPostType.values()).map(Enum::name).collect(Collectors.toSet());
    private static final String CANNOT_POST = "You cannot post on behalf of this club";

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final PausedClubs pausedClubs;
    private final AudienceService audiences;
    private final CampusMailer mailer;
    private final NotificationService notifications;
    private final AuditService audit;
    private final FeedViews views;

    public FeedService(MongoOperations mongo, ClubAccess access, PausedClubs pausedClubs, AudienceService audiences, CampusMailer mailer,
            NotificationService notifications, AuditService audit, FeedViews views) {
        this.mongo = mongo;
        this.access = access;
        this.pausedClubs = pausedClubs;
        this.audiences = audiences;
        this.mailer = mailer;
        this.notifications = notifications;
        this.audit = audit;
        this.views = views;
    }

    // ---------------------------------------------------------------- posting

    public FeedPostView createPost(AuthUser actor, CreatePostBody payload) {
        FeedPostType type = payload.type() == null ? FeedPostType.ANNOUNCEMENT : FeedPostType.valueOf(payload.type());
        if (!MANUAL_POST_TYPES.contains(type)) {
            throw AppException.badRequest("Invalid post type", ErrorCode.VALIDATION_ERROR);
        }
        if (payload.title() == null || payload.title().isBlank()) {
            throw AppException.badRequest("Post title is required", ErrorCode.VALIDATION_ERROR);
        }
        Club club = access.assertPermission(actor, payload.club(), ClubPermission.POST_UPDATES, CANNOT_POST).club();
        if (club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("Only active clubs can post updates", ErrorCode.CLUB_NOT_ACTIVE);
        }

        // Who it goes to: the club picks the audience, so nobody else is notified or e-mailed.
        Audience audience = audiences.parse(club, FeedDtos.audiencePayload(payload.audience(), payload.visibility()));
        Resolved reach = audiences.resolve(club, audience);
        if (reach.notifyTo() != null && reach.notifyTo().stream().allMatch(actor.id()::equals)) {
            throw AppException.badRequest("Nobody matches this audience yet — choose other roles, departments or people", ErrorCode.VALIDATION_ERROR);
        }
        boolean sendEmail = !Boolean.FALSE.equals(payload.sendEmail());

        String eventId = payload.event() == null || payload.event().isEmpty() ? null : payload.event();
        if (type == FeedPostType.EVENT_UPDATE || eventId != null) {
            if (eventId == null) {
                throw AppException.badRequest("Event updates must reference an event", ErrorCode.VALIDATION_ERROR);
            }
            assertPublishedEventOf(eventId, club);
        }

        FeedPost post = new FeedPost();
        post.setType(type);
        post.setClub(club.getId());
        post.setEvent(eventId);
        post.setAuthor(actor.id());
        post.setTitle(payload.title().strip());
        post.setBody(payload.body() == null ? "" : payload.body().strip());
        post.setImage(payload.image());
        post.setVisibility(reach.visibility());
        post.setAudience(audienceOf(club, audience));
        if (reach.recipients() != null) {
            Set<String> recipients = new LinkedHashSet<>(reach.recipients());
            recipients.add(actor.id());
            post.setRecipients(new ArrayList<>(recipients));
        }
        post.setRecipientCount(reach.notifyTo() == null ? null : (int) reach.notifyTo().stream().filter(id -> !id.equals(actor.id())).count());
        post = mongo.insert(post);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", club.getId());
        metadata.put("type", type.name());
        audit.record(AuditAction.FEED_POST_CREATED, actor.id(), "FeedPost", post.getId(), metadata);
        announce(actor, club, post, audience, reach, eventId, sendEmail, payload.body());

        return views.of(List.of(post)).get(0);
    }

    private void assertPublishedEventOf(String eventId, Club club) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(eventId)));
        query.fields().include("club").include("status");
        Document event = mongo.findOne(query, Document.class, "events");
        boolean published = event != null && EventStatus.PUBLIC.stream().anyMatch(status -> status.name().equals(event.getString("status")));
        if (!published || event.getObjectId("club") == null || !event.getObjectId("club").toHexString().equals(club.getId())) {
            throw AppException.badRequest("Updates can only reference this club's published events", ErrorCode.VALIDATION_ERROR);
        }
    }

    private FeedAudience audienceOf(Club club, Audience audience) {
        FeedAudience stored = new FeedAudience();
        stored.setMode(audience.mode());
        stored.setRoles(new ArrayList<>(audience.roles()));
        stored.setDepartments(new ArrayList<>(audience.departments()));
        stored.setBatches(new ArrayList<>(audience.batches()));
        stored.setPeople(audience.users().size());
        stored.setIncludeMentor(audience.includeMentor());
        stored.setLabel(audiences.describe(club, audience));
        return stored;
    }

    /** Announcements are delivered as notifications (the campus feed shows events only), after the post is saved. */
    private void announce(AuthUser actor, Club club, FeedPost post, Audience audience, Resolved reach, String eventId, boolean sendEmail, String rawBody) {
        String title = club.getName() + ": " + post.getTitle();
        String body = rawBody == null ? "" : rawBody;
        String message = body.length() > 300 ? body.substring(0, 300) : body;
        String link = eventId != null ? "/events/" + eventId : "/clubs/" + club.getId();
        List<String> exclude = List.of(actor.id());

        if (reach.notifyTo() != null) {
            notifications.notify(reach.notifyTo(), Notice.builder()
                    .type(NotificationType.ANNOUNCEMENT).title(title).message(message).link(link).exclude(exclude)
                    .push(Notice.PushTargets.only(reach.pushTo()))
                    .build());
        } else {
            notifications.notifyAllUsers(Broadcast.builder()
                    .type(NotificationType.ANNOUNCEMENT).title(title).message(message).link(link).exclude(exclude)
                    .pushTo(reach.pushTo())
                    .build());
        }
        if (sendEmail) {
            mailer.sendAnnouncementEmails(post, club, actor, audience.mode().name(), reach.emailTo(), link);
        }
    }

    // ---------------------------------------------------------------- reading

    /** Clubs the viewer belongs to, the ones whose role lets them post there, and the clubs they mentor. */
    private record Insider(List<ObjectId> memberOf, List<ObjectId> posting, List<ObjectId> mentored) {
    }

    private Insider insider(AuthUser actor) {
        ObjectId viewer = new ObjectId(actor.id());
        Query memberships = Query.query(Criteria.where("user").is(viewer).and("status").is(MembershipStatus.APPROVED.name()));
        memberships.fields().include("club").include("role");
        List<ClubMembership> rows = mongo.find(memberships, ClubMembership.class);

        Map<String, Club> roles = Map.of();
        if (!rows.isEmpty()) {
            // One query for the roles of every club the viewer is in (whose role lets them post where).
            Query clubs = Query.query(Criteria.where("_id").in(rows.stream().map(row -> new ObjectId(row.getClub())).toList()));
            clubs.fields().include("roles");
            roles = mongo.find(clubs, Club.class).stream().collect(Collectors.toMap(Club::getId, Function.identity()));
        }
        Map<String, Club> clubRoles = roles;
        List<ObjectId> memberOf = rows.stream().map(row -> new ObjectId(row.getClub())).toList();
        List<ObjectId> posting = rows.stream()
                .filter(row -> clubRoles.containsKey(row.getClub()) && ClubRoles.has(clubRoles.get(row.getClub()), row.getRole(), ClubPermission.POST_UPDATES))
                .map(row -> new ObjectId(row.getClub()))
                .toList();

        Query mentoredQuery = Query.query(Criteria.where("mentor").is(viewer));
        mentoredQuery.fields().include("_id");
        List<ObjectId> mentored = mongo.find(mentoredQuery, Document.class, "clubs").stream().map(club -> club.getObjectId("_id")).toList();
        return new Insider(memberOf, posting, mentored);
    }

    public Page<FeedPostView> listFeed(AuthUser actor, FeedQuery query) {
        Pagination pagination = Pagination.of(query.page(), query.limit(), 10);
        List<Criteria> filter = new ArrayList<>();

        String typeList = query.types() != null && !query.types().isEmpty() ? query.types() : query.type() != null ? query.type() : "";
        List<String> types = Arrays.stream(typeList.split(",")).map(type -> type.trim().toUpperCase(Locale.ROOT)).filter(TYPE_NAMES::contains).toList();
        if (!types.isEmpty()) {
            filter.add(Criteria.where("type").in(types));
        }
        if (query.event() != null && !query.event().isEmpty()) {
            filter.add(Criteria.where("event").is(new ObjectId(query.event())));
        }

        Insider insider = insider(actor);
        if (query.club() != null && !query.club().isEmpty()) {
            filter.add(Criteria.where("club").is(new ObjectId(query.club())));
        } else if ("my-clubs".equals(query.scope())) {
            filter.add(Criteria.where("club").in(insider.memberOf()));
        }

        // Members-only posts are visible to that club's members and mentor only (not the university admin).
        List<ObjectId> inside = new ArrayList<>(insider.memberOf());
        inside.addAll(insider.mentored());
        // Announcements for a chosen audience: those people, plus the club's posting team and mentor.
        List<ObjectId> team = new ArrayList<>(insider.posting());
        team.addAll(insider.mentored());
        filter.add(new Criteria().orOperator(
                Criteria.where("visibility").is(FeedVisibility.PUBLIC.name()),
                Criteria.where("visibility").is(FeedVisibility.MEMBERS.name()).and("club").in(inside),
                Criteria.where("visibility").is(FeedVisibility.AUDIENCE.name()).and("recipients").is(new ObjectId(actor.id())),
                Criteria.where("visibility").is(FeedVisibility.AUDIENCE.name()).and("club").in(team)));

        // Suspended or archived clubs' posts stay visible to their own members and mentor only.
        Set<String> paused = pausedClubs.ids();
        if (!paused.isEmpty()) {
            filter.add(new Criteria().orOperator(
                    Criteria.where("club").nin(paused.stream().map(ObjectId::new).toList()),
                    Criteria.where("club").in(inside)));
        }

        Criteria criteria = new Criteria().andOperator(filter);
        Query page = Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt")).skip(pagination.skip()).limit(pagination.limit());
        // Recipient lists can hold a whole department; they are only for this filter, never sent back.
        page.fields().exclude("recipients");
        List<FeedPost> posts = mongo.find(page, FeedPost.class);
        long total = mongo.count(Query.query(criteria), FeedPost.class);
        return Page.of(views.of(posts), pagination, total);
    }

    // ---------------------------------------------------------------- removing

    public void deletePost(AuthUser actor, String id) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(id)));
        query.fields().include("author").include("isSystem").include("club").include("type");
        FeedPost post = mongo.findOne(query, FeedPost.class);
        if (post == null) {
            throw AppException.notFound("Post not found");
        }
        boolean isAuthor = actor.id().equals(post.getAuthor()) && !post.isSystem();
        if (!isAuthor) {
            ClubContext context = access.context(actor, post.getClub());
            if (!context.has(ClubPermission.MANAGE_CLUB)) {
                throw AppException.forbidden("You cannot delete this post");
            }
        }
        mongo.remove(Query.query(Criteria.where("_id").is(new ObjectId(post.getId()))), FeedPost.class);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", post.getClub());
        metadata.put("type", post.getType() == null ? null : post.getType().name());
        audit.record(AuditAction.FEED_POST_DELETED, actor.id(), "FeedPost", post.getId(), metadata);
    }

    // ---------------------------------------------------------------- composing

    /** How many people an announcement would reach, for the composer. */
    public AudiencePreview previewAudience(AuthUser actor, AudiencePreviewBody payload) {
        Club club = access.assertPermission(actor, payload.club(), ClubPermission.POST_UPDATES, CANNOT_POST).club();
        Audience audience = audiences.parse(club, FeedDtos.audiencePayload(payload.audience(), payload.visibility()));
        Map<String, Object> reach = audiences.count(club, audience);
        return new AudiencePreview(reach.get("count"), reach.get("email"), audiences.describe(club, audience));
    }
}
