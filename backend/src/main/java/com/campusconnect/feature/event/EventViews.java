package com.campusconnect.feature.event;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.PageMeta;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventRevision;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.Expense;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.RevisionStatus;
import com.campusconnect.domain.social.LikeTargetType;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.feature.like.LikeService;
import com.campusconnect.feature.registration.RegistrationForms;
import com.campusconnect.feature.registration.ScheduleClashService;
import com.campusconnect.feature.registration.Waitlist;
import com.campusconnect.feature.team.Teams;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading events (the query side of EventService.js): the detail page with everything the viewer may see, the
 * campus events feed (cached for a few seconds, the same for every viewer apart from their own registration, follow
 * and like) and the organisers' list. A club's events tab (GET /api/clubs/:clubId/events) belongs to the clubs
 * feature.
 */
@Service
public class EventViews {

    static final List<String> TIMEFRAMES = List.of("upcoming", "ongoing", "past", "all");

    private final MongoOperations mongo;
    private final EventStore events;
    private final EventJson eventJson;
    private final EventFeedCache feedCache;
    private final ClubAccess access;
    private final PausedClubs pausedClubs;
    private final Refs refs;
    private final JsonMapper json;
    private final Waitlist waitlist;
    private final Teams teams;
    private final RegistrationForms forms;
    private final LikeService likes;
    private final ScheduleClashService clashes;
    private final EventReminders reminders;

    public EventViews(MongoOperations mongo, EventStore events, EventJson eventJson, EventFeedCache feedCache, ClubAccess access, PausedClubs pausedClubs, Refs refs,
            JsonMapper json, Waitlist waitlist, Teams teams, RegistrationForms forms, LikeService likes, ScheduleClashService clashes, EventReminders reminders) {
        this.mongo = mongo;
        this.events = events;
        this.eventJson = eventJson;
        this.feedCache = feedCache;
        this.access = access;
        this.pausedClubs = pausedClubs;
        this.refs = refs;
        this.json = json;
        this.waitlist = waitlist;
        this.teams = teams;
        this.forms = forms;
        this.likes = likes;
        this.clashes = clashes;
        this.reminders = reminders;
    }

    private static boolean isStaff(ClubContext context) {
        return context.isMentor() || context.has(ClubPermission.MANAGE_EVENTS) || context.has(ClubPermission.PUBLISH_EVENTS);
    }

    // ---------------------------------------------------------------- detail

    public Map<String, Object> getEventDetail(AuthUser actor, String eventId) {
        Event event = events.find(eventId);
        ClubContext context = access.context(actor, event.getClub());
        EventRegistration found = actor == null ? null : mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(event.getId()))
                .and("user").is(new ObjectId(actor.id()))), EventRegistration.class);
        boolean isPublic = EventStatus.PUBLIC.contains(event.getStatus()) || (event.getStatus() == EventStatus.CANCELLED && event.getPublishedAt() != null);
        boolean staff = isStaff(context);
        if (!isPublic && !staff) {
            throw AppException.notFound("Event not found");
        }

        boolean isTeamEvent = event.getParticipationMode() == ParticipationMode.TEAM;
        boolean active = found != null && (found.getStatus() == RegistrationStatus.REGISTERED || found.getStatus() == RegistrationStatus.WAITLISTED);
        Instant now = Instant.now();

        Integer position = found == null ? null : waitlist.waitlistPosition(found);
        Map<String, Object> team = actor != null && isTeamEvent && active && found.getTeam() != null ? teams.getTeamView(found.getTeam(), event) : null;
        List<Map<String, Object>> invites = actor != null && isTeamEvent && !active ? teams.invitesFor(actor, event.getId()) : List.of();
        long galleryCount = EventStatus.PUBLIC.contains(event.getStatus())
                ? mongo.count(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("status").is("APPROVED")), "eventmedias")
                : 0;
        Map<String, Object> myAnswers = active ? forms.myAnswers(event, found) : null;
        boolean likedByMe = actor != null && likes.likedAmong(actor, LikeTargetType.EVENT, List.of(event.getId())).contains(event.getId());
        List<Map<String, Object>> clashList = actor != null && actor.accountType() == AccountType.STUDENT && !active && event.getStatus() == EventStatus.PUBLISHED
                && event.getStartAt().isAfter(now) ? clashes.clashesFor(actor.id(), event) : null;
        Map<String, Object> revision = staff && event.getRevision() != null ? describeRevision(event) : null;

        Map<String, Object> view = eventJson.serialize(event, eventJson.populate(event, context.club()));
        view.put("galleryCount", galleryCount);
        if (staff || context.isAdmin()) {
            view.put("budgetItems", eventJson.plain(event.getBudgetItems(), ArrayList.class));
            view.put("equipment", eventJson.plain(event.getEquipment(), ArrayList.class));
            view.put("budgetNote", event.getBudgetNote());
            view.put("budgetTotal", Values.jsNumber(budgetTotal(event)));
            view.put("expenses", eventJson.plain(event.getExpenses(), LinkedHashMap.class));
            view.put("expensesTotal", Values.jsNumber(event.getExpenses() == null ? 0 : event.getExpenses().getItems().stream().mapToDouble(Expense::getAmount).sum()));
        }
        view.put("myAnswers", myAnswers);
        view.put("likedByMe", likedByMe);
        // On hold while the club is suspended or archived: visible to those involved, closed to registration.
        boolean onHold = context.club().getStatus() != ClubStatus.ACTIVE;
        view.put("onHold", onHold);
        if (onHold && event.getStatus() == EventStatus.PUBLISHED) {
            view.put("registrationState", "ON_HOLD");
        }
        if (actor == null) {
            view.put("viewer", null);
        } else {
            Map<String, Object> viewer = viewerFor(context, event, found, position, now);
            if (isTeamEvent) {
                viewer.put("team", team);
                viewer.put("teamRole", active ? found.getTeamRole() : null);
                viewer.put("invites", invites);
            }
            // Other events this student holds a place for at the same time (they'd have to switch).
            if (clashList != null) {
                viewer.put("clashes", clashList);
            }
            if (context.has(ClubPermission.SEND_REMINDERS)) {
                viewer.put("reminders", reminders.reminderStatus(event));
            }
            view.put("viewer", viewer);
        }
        if (revision != null) {
            view.put("revision", revision);
        }
        return view;
    }

    public static double budgetTotal(Event event) {
        return event.getBudgetItems().stream().mapToDouble(row -> row.getQuantity() * row.getUnitCost()).sum();
    }

    private static Map<String, Object> viewerFor(ClubContext context, Event event, EventRegistration registration, Integer position, Instant now) {
        RevisionStatus revisionStatus = event.getRevision() == null ? null : event.getRevision().getStatus();
        Map<String, Object> viewer = new LinkedHashMap<>();
        viewer.put("isMentor", context.isMentor());
        viewer.put("isAdmin", context.isAdmin());
        viewer.put("role", context.role());
        viewer.put("canManage", context.has(ClubPermission.MANAGE_EVENTS));
        viewer.put("canPublish", context.has(ClubPermission.PUBLISH_EVENTS));
        viewer.put("canViewParticipants", context.isMentor() || context.has(ClubPermission.VIEW_PARTICIPANTS));
        viewer.put("canManageParticipants", context.has(ClubPermission.MANAGE_PARTICIPANTS));
        viewer.put("canManageResults", context.has(ClubPermission.MANAGE_RESULTS));
        viewer.put("canPublishResults", context.has(ClubPermission.PUBLISH_RESULTS));
        // Check-in at the door: the president opens it, every officer scans.
        viewer.put("canManageCheckIn", context.has(ClubPermission.MANAGE_CHECK_IN));
        viewer.put("canMarkAttendance", context.has(ClubPermission.MARK_ATTENDANCE));
        viewer.put("canSendReminders", context.has(ClubPermission.SEND_REMINDERS));
        viewer.put("canReview", context.isMentor() && event.getStatus() == EventStatus.PENDING_APPROVAL);
        viewer.put("canReviewChanges", context.isMentor() && revisionStatus == RevisionStatus.PENDING_APPROVAL);
        viewer.put("canPublishChanges", context.has(ClubPermission.PUBLISH_EVENTS) && revisionStatus == RevisionStatus.APPROVED);
        // Details can be edited until the event starts (published events go through the mentor again).
        viewer.put("canEdit", context.has(ClubPermission.MANAGE_EVENTS)
                && List.of(EventStatus.DRAFT, EventStatus.NEEDS_CHANGES, EventStatus.APPROVED, EventStatus.PUBLISHED).contains(event.getStatus())
                && event.getStartAt().isAfter(now));
        if (registration == null) {
            viewer.put("registration", null);
        } else {
            Map<String, Object> mine = new LinkedHashMap<>();
            mine.put("_id", registration.getId());
            mine.put("status", registration.getStatus());
            mine.put("registeredAt", registration.getRegisteredAt());
            mine.put("promotedAt", registration.getPromotedAt());
            mine.put("waitlistPosition", position);
            mine.put("ticketCode", registration.getTicketCode());
            mine.put("checkedInAt", registration.getCheckedInAt());
            mine.put("checkInMethod", registration.getCheckInMethod());
            viewer.put("registration", mine);
        }
        return viewer;
    }

    /** Proposed changes as shown to the club and the mentor: each changed field with its live and proposed value. */
    public Map<String, Object> describeRevision(Event event) {
        EventRevision revision = event.getRevision();
        if (revision == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) Refs.plain(new Document(revision.getChanges() == null ? Map.of() : revision.getChanges()));
        List<String> userIds = Arrays.asList(revision.getRequestedBy(), revision.getReviewedBy(), Values.id(changes.get("organizer")), event.getOrganizer());
        List<String> venueIds = Arrays.asList(Values.id(changes.get("venue")), event.getVenue());
        Map<String, Map<String, Object>> users = refs.users(userIds.stream().filter(Objects::nonNull).toList(), "name");
        Map<String, Map<String, Object>> venues = refs.load("venues", venueIds.stream().filter(Objects::nonNull).toList(), "name", "location", "capacity");

        List<Map<String, Object>> diff = new ArrayList<>();
        for (String field : revision.getFields()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", field);
            row.put("label", EventRules.FIELD_LABELS.getOrDefault(field, field));
            row.put("from", display(field, eventJson.currentValue(event, field), users, venues));
            row.put("to", display(field, changes.get(field), users, venues));
            diff.add(row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", revision.getStatus());
        view.put("fields", revision.getFields());
        view.put("note", revision.getNote());
        view.put("changes", changes);
        view.put("diff", diff);
        view.put("requestedBy", revision.getRequestedBy() == null ? null : users.get(revision.getRequestedBy()));
        view.put("requestedAt", revision.getRequestedAt());
        view.put("reviewComment", revision.getReviewComment());
        view.put("reviewedBy", revision.getReviewedBy() == null ? null : users.get(revision.getReviewedBy()));
        view.put("reviewedAt", revision.getReviewedAt());
        return view;
    }

    private static Object display(String field, Object value, Map<String, Map<String, Object>> users, Map<String, Map<String, Object>> venues) {
        if ("venue".equals(field)) {
            return value == null ? null : venues.get(Values.id(value));
        }
        if ("organizer".equals(field)) {
            return value == null ? null : users.get(Values.id(value));
        }
        return value instanceof Date date ? date.toInstant() : value;
    }

    // ---------------------------------------------------------------- the campus events feed

    /** One page of a listing with its meta (pagination plus the listing's own fields). */
    public record Listing(List<Map<String, Object>> items, Map<String, Object> meta) {
    }

    /** What is cached for a feed page: the cards, and what their registration state depends on. */
    public record FeedPage(List<FeedCard> items, long total, Map<String, Long> counts) {
    }

    public record FeedCard(Map<String, Object> event, EventRules.Window window) {
    }

    private static Date date(Instant instant) {
        return Date.from(instant);
    }

    private static Document timeframeFilter(String timeframe, Instant now) {
        return switch (timeframe) {
            case "ongoing" -> new Document("status", EventStatus.PUBLISHED.name()).append("startAt", new Document("$lte", date(now))).append("endAt", new Document("$gt", date(now)));
            case "past" -> new Document("$or", List.of(new Document("status", EventStatus.COMPLETED.name()),
                    new Document("status", EventStatus.PUBLISHED.name()).append("endAt", new Document("$lte", date(now)))));
            case "all" -> new Document("status", new Document("$in", EventStatus.PUBLIC.stream().map(Enum::name).toList()));
            default -> new Document("status", EventStatus.PUBLISHED.name()).append("startAt", new Document("$gt", date(now)));
        };
    }

    private static Sort timeframeSort(String timeframe) {
        return switch (timeframe) {
            case "ongoing" -> Sort.by("endAt");
            case "past", "all" -> Sort.by(Sort.Direction.DESC, "startAt");
            default -> Sort.by("startAt");
        };
    }

    /** CastError on a malformed ?club= (the original answered "Invalid club: x"). */
    static ObjectId clubParam(String club) {
        if (!ObjectId.isValid(club)) {
            throw AppException.badRequest("Invalid club: " + club);
        }
        return new ObjectId(club);
    }

    /** Search/category/club filters shared by the feed list and its per-tab counts; paused clubs' events are left out. */
    private static Document discoveryFilters(String category, String club, String search, Set<String> paused) {
        Document filter = new Document();
        if (!paused.isEmpty()) {
            filter.put("club", new Document("$nin", paused.stream().map(ObjectId::new).toList()));
        }
        if (!Texts.isBlank(category)) {
            filter.put("category", category.toUpperCase(Locale.ROOT));
        }
        if (club != null && !club.isEmpty()) {
            filter.put("club", paused.contains(club) ? new Document("$in", List.of()) : clubParam(club));
        }
        if (search != null && !search.isEmpty()) {
            filter.put("title", new Document("$regex", Texts.searchPattern(search)).append("$options", "i"));
        }
        return filter;
    }

    public Listing listEvents(AuthUser actor, String page, String limit, String timeframe, String category, String club, String search, String registrationOpen,
            String withCounts) {
        Pagination pagination = Pagination.of(page, limit, 12);
        String frame = timeframe != null && TIMEFRAMES.contains(timeframe) ? timeframe : "upcoming";
        boolean openOnly = "true".equals(registrationOpen);
        boolean counted = "true".equals(withCounts);
        String key = json.writeValueAsString(List.of(frame, pagination.skip(), pagination.limit(), category == null ? "" : category, club == null ? "" : club,
                search == null ? "" : search, openOnly, counted));

        // The page itself is the same for every viewer, so it's shared for a few seconds (cleared on any event,
        // result, club or venue write); only the viewer's own registration, follow and like are looked up each time.
        String cached = feedCache.remember(key, () -> json.writeValueAsString(loadFeedPage(frame, pagination, category, club, search, openOnly, counted)));
        FeedPage shared = json.readValue(cached, FeedPage.class);

        // Registration windows open and close by the clock, so their state is worked out fresh.
        Instant now = Instant.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (FeedCard card : shared.items()) {
            Map<String, Object> item = new LinkedHashMap<>(card.event());
            item.put("registrationState", EventRules.registrationWindowState(card.window(), now));
            items.add(item);
        }
        eventJson.attachViewer(actor, items);

        PageMeta pageMeta = pagination.meta(shared.total());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("timeframe", frame);
        if (shared.counts() != null) {
            meta.put("counts", shared.counts());
        }
        meta.put("total", pageMeta.total());
        meta.put("page", pageMeta.page());
        meta.put("limit", pageMeta.limit());
        meta.put("totalPages", pageMeta.totalPages());
        return new Listing(items, meta);
    }

    private FeedPage loadFeedPage(String frame, Pagination pagination, String category, String club, String search, boolean openOnly, boolean counted) {
        Instant now = Instant.now();
        Set<String> paused = pausedClubs.ids();
        Document filter = timeframeFilter(frame, now);
        filter.putAll(discoveryFilters(category, club, search, paused));
        if (openOnly) {
            filter.put("status", EventStatus.PUBLISHED.name());
            filter.put("registrationClosed", false);
            filter.put("registrationStart", new Document("$lte", date(now)));
            filter.put("registrationEnd", new Document("$gte", date(now)));
            // Unlimited (null or never set) or not yet full.
            filter.put("$expr", new Document("$or", List.of(
                    new Document("$eq", Arrays.asList(new Document("$ifNull", Arrays.asList("$maxParticipants", null)), null)),
                    new Document("$lt", List.of("$registeredCount", "$maxParticipants")))));
        }
        Query query = new BasicQuery(filter).with(timeframeSort(frame)).skip(pagination.skip()).limit(pagination.limit());
        List<Event> page = mongo.find(query, Event.class);
        long total = mongo.count(new BasicQuery(filter), Event.class);
        Map<String, Long> counts = null;
        if (counted) {
            counts = new LinkedHashMap<>();
            for (String tab : List.of("upcoming", "ongoing", "past")) {
                Document tabFilter = timeframeFilter(tab, now);
                tabFilter.putAll(discoveryFilters(category, club, search, paused));
                counts.put(tab, mongo.count(new BasicQuery(tabFilter), Event.class));
            }
        }
        List<Map<String, Object>> items = eventJson.serializeAll(page);
        eventJson.attachResults(page, items);
        List<FeedCard> cards = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            cards.add(new FeedCard(items.get(i), EventRules.Window.of(page.get(i))));
        }
        return new FeedPage(cards, total, counts);
    }

    // ---------------------------------------------------------------- organisers' lists

    /** Clubs whose events the user helps run: mentored clubs for faculty, officer roles for students. */
    public List<String> manageableClubIds(AuthUser actor) {
        if (ClubAccess.isFaculty(actor)) {
            Query query = Query.query(Criteria.where("mentor").is(new ObjectId(actor.id())));
            query.fields().include("_id");
            return mongo.find(query, Club.class).stream().map(Club::getId).toList();
        }
        return access.clubIdsWithAnyPermission(actor.id(), List.of(ClubPermission.MANAGE_EVENTS, ClubPermission.PUBLISH_EVENTS, ClubPermission.VIEW_PARTICIPANTS));
    }

    public Listing listManagedEvents(AuthUser actor, String page, String limit, String club, String status, String search) {
        Pagination pagination = Pagination.of(page, limit, 20);
        // Only clubs the user runs (officer roles) or mentors; the university admin has none.
        List<String> clubIds = manageableClubIds(actor);
        Document filter = new Document("club", new Document("$in", clubIds.stream().map(ObjectId::new).toList()));
        if (club != null && !club.isEmpty()) {
            ObjectId chosen = clubParam(club);
            filter.put("club", clubIds.contains(club) ? chosen : new Document("$in", List.of()));
        }
        if (status != null && !status.isEmpty()) {
            List<String> statuses = Arrays.stream(status.split(",")).map(value -> value.strip().toUpperCase(Locale.ROOT))
                    .filter(value -> Arrays.stream(EventStatus.values()).anyMatch(known -> known.name().equals(value))).toList();
            if (!statuses.isEmpty()) {
                // "Awaiting approval" also covers edits to published events that wait for the mentor.
                if (statuses.contains(EventStatus.PENDING_APPROVAL.name())) {
                    filter.put("$or", List.of(new Document("status", new Document("$in", statuses)),
                            new Document("revision.status", RevisionStatus.PENDING_APPROVAL.name())));
                } else {
                    filter.put("status", new Document("$in", statuses));
                }
            }
        }
        if (search != null && !search.isEmpty()) {
            filter.put("title", new Document("$regex", Texts.searchPattern(search)).append("$options", "i"));
        }
        List<Event> managed = mongo.find(new BasicQuery(filter).with(Sort.by(Sort.Direction.DESC, "updatedAt")).skip(pagination.skip()).limit(pagination.limit()), Event.class);
        long total = mongo.count(new BasicQuery(filter), Event.class);
        List<Map<String, Object>> items = eventJson.serializeAll(managed);
        for (int i = 0; i < managed.size(); i++) {
            EventRevision revision = managed.get(i).getRevision();
            items.get(i).put("revisionStatus", revision == null ? null : revision.getStatus());
            items.get(i).put("revisionFields", revision == null ? List.of() : revision.getFields());
        }
        return new Listing(items, metaOf(pagination.meta(total)));
    }

    private static Map<String, Object> metaOf(PageMeta pageMeta) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", pageMeta.total());
        meta.put("page", pageMeta.page());
        meta.put("limit", pageMeta.limit());
        meta.put("totalPages", pageMeta.totalPages());
        return meta;
    }
}
