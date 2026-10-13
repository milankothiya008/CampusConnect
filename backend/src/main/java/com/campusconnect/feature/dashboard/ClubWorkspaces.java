package com.campusconnect.feature.dashboard;

import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.util.Parallel;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.InterviewSlot;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.checkin.CheckInService;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * A club officer's workspace on the student dashboard (DashboardService.clubWorkspace): the club's events by stage,
 * results still to publish, gallery uploads to review, the recruitment drive in progress and, for whoever manages the
 * club, its insights. Each part is shown only to the roles holding the matching club permission.
 */
@Component
public class ClubWorkspaces {

    static final int INSIGHT_EVENTS = 12;
    static final List<String> WORKSPACE_EVENTS = names(EventStatus.DRAFT, EventStatus.NEEDS_CHANGES, EventStatus.PENDING_APPROVAL, EventStatus.APPROVED, EventStatus.PUBLISHED);
    static final List<String> PIPELINE = names(EventStatus.DRAFT, EventStatus.NEEDS_CHANGES, EventStatus.PENDING_APPROVAL, EventStatus.APPROVED);
    static final List<String> HOSTED = names(EventStatus.PUBLISHED, EventStatus.COMPLETED);
    static final List<String> DRIVE_IN_PROGRESS = names(RecruitmentStatus.DRAFT, RecruitmentStatus.PENDING_APPROVAL, RecruitmentStatus.NEEDS_CHANGES,
            RecruitmentStatus.APPROVED, RecruitmentStatus.PUBLISHED);
    static final List<String> IN_PLAY = names(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS, ApplicationStatus.OFFERED, ApplicationStatus.RESERVE);
    static final List<ApplicationStatus> ACTIVE = List.of(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS);

    private final MongoOperations mongo;
    private final EventCards cards;
    private final CheckInService checkIns;

    public ClubWorkspaces(MongoOperations mongo, EventCards cards, CheckInService checkIns) {
        this.mongo = mongo;
        this.cards = cards;
        this.checkIns = checkIns;
    }

    /** @param clubView the club as the dashboard shows it (populated "name logo category status president mentor ...") */
    public Map<String, Object> workspace(ClubMembership membership, Club club, Map<String, Object> clubView) {
        ObjectId clubId = new ObjectId(club.getId());
        List<String> permissions = ClubRoles.permissionsFor(club, membership.getRole());
        Instant now = Instant.now();

        try (Parallel parallel = new Parallel()) {
            Supplier<Map<String, Object>> recruitment = has(permissions, ClubPermission.MANAGE_RECRUITMENT) ? parallel.fork(() -> recruitmentSummary(clubId, now)) : () -> null;
            Supplier<Long> memberCount = parallel.fork(() -> mongo.count(Query.query(Criteria.where("club").is(clubId).and("status").is(MembershipStatus.APPROVED.name())),
                    ClubMembership.class));
            Supplier<List<Map<String, Object>>> events = parallel.fork(() -> cards.find(Query.query(Criteria.where("club").is(clubId).and("status").in(WORKSPACE_EVENTS))
                    .with(Sort.by("startAt")), EventCards.Populate.VENUE, "reviewComment", "revision.status", "revision.fields", "revision.reviewComment"));
            Supplier<List<Map<String, Object>>> completed = parallel.fork(() -> completedEvents(clubId));
            // Gallery uploads waiting for the president or vice-president, per event; result states; insights.
            Supplier<List<Map<String, Object>>> galleryReview = has(permissions, ClubPermission.MODERATE_GALLERY) ? parallel.fork(() -> galleryReview(clubId)) : List::of;
            Supplier<Map<String, String>> resultStatuses = parallel.fork(() -> resultStatuses(completed.get()));
            Supplier<Map<String, Object>> insights = has(permissions, ClubPermission.MANAGE_CLUB) ? parallel.fork(() -> insights(clubId, memberCount.get(), now)) : () -> null;

            List<Map<String, Object>> clubEvents = events.get();
            List<Map<String, Object>> published = byStatus(clubEvents, EventStatus.PUBLISHED);
            Map<String, String> resultByEvent = resultStatuses.get();

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("club", clubView);
            view.put("role", membership.getRole());
            view.put("roleName", ClubRoles.roleName(club, membership.getRole()));
            view.put("permissions", permissions);
            view.put("memberCount", memberCount.get());
            view.put("recruitment", recruitment.get());
            view.put("drafts", byStatus(clubEvents, EventStatus.DRAFT));
            view.put("needsChanges", byStatus(clubEvents, EventStatus.NEEDS_CHANGES));
            view.put("pendingApproval", byStatus(clubEvents, EventStatus.PENDING_APPROVAL));
            view.put("readyToPublish", byStatus(clubEvents, EventStatus.APPROVED));
            // Edits to published events: waiting for the mentor, sent back, or approved and ready to go live.
            view.put("changesInReview", clubEvents.stream().filter(event -> event.get("revision") != null).toList());
            view.put("upcoming", published.stream().filter(event -> event.get("endAt") instanceof Instant endAt && endAt.isAfter(now)).toList());
            view.put("awaitingCompletion", published.stream().filter(event -> event.get("endAt") instanceof Instant endAt && !endAt.isAfter(now)).toList());
            view.put("resultsPending", has(permissions, ClubPermission.MANAGE_RESULTS)
                    ? completed.get().stream()
                            .filter(event -> !ResultStatus.PUBLISHED.name().equals(resultByEvent.get((String) event.get("_id"))))
                            .map(event -> {
                                Map<String, Object> row = new LinkedHashMap<>(event);
                                row.put("resultStatus", resultByEvent.get((String) event.get("_id")));
                                return row;
                            })
                            .toList()
                    : List.of());
            view.put("galleryReview", galleryReview.get());
            view.put("insights", insights.get());
            return view;
        }
    }

    private static boolean has(List<String> permissions, ClubPermission permission) {
        return permissions.contains(permission.name());
    }

    private static List<String> names(Enum<?>... values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }

    private static List<Map<String, Object>> byStatus(List<Map<String, Object>> events, EventStatus status) {
        return events.stream().filter(event -> status.name().equals(event.get("status"))).toList();
    }

    /** The club's last 20 completed events (for the results still to publish). */
    private List<Map<String, Object>> completedEvents(ObjectId clubId) {
        Query query = Query.query(Criteria.where("club").is(clubId).and("status").is(EventStatus.COMPLETED.name())).with(Sort.by(Sort.Direction.DESC, "startAt")).limit(20);
        query.fields().include("title").include("startAt");
        return mongo.find(query, Document.class, "events").stream().map(row -> Refs.shape(row, "title", "startAt")).toList();
    }

    /** Result status (DRAFT or PUBLISHED) per event id, for events that have a result. */
    private Map<String, String> resultStatuses(List<Map<String, Object>> events) {
        Map<String, String> statuses = new HashMap<>();
        if (events.isEmpty()) {
            return statuses;
        }
        Query query = Query.query(Criteria.where("event").in(events.stream().map(row -> new ObjectId((String) row.get("_id"))).toList()));
        query.fields().include("event").include("status");
        mongo.find(query, Document.class, "eventresults").forEach(row -> statuses.put(row.getObjectId("event").toHexString(), row.getString("status")));
        return statuses;
    }

    /** Gallery uploads waiting for review, per event, most first. */
    private List<Map<String, Object>> galleryReview(ObjectId clubId) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("club", clubId).append("status", GalleryStatus.PENDING.name())),
                new Document("$group", new Document("_id", "$event").append("pending", new Document("$sum", 1))),
                new Document("$lookup", new Document("from", "events").append("localField", "_id").append("foreignField", "_id").append("as", "event")
                        .append("pipeline", List.of(new Document("$project", new Document("title", 1))))),
                new Document("$unwind", "$event"),
                new Document("$project", new Document("_id", "$event._id").append("title", "$event.title").append("pending", 1)),
                new Document("$sort", new Document("pending", -1)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Document row : mongo.getCollection("eventmedias").aggregate(pipeline)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("_id", Refs.plain(row.get("_id")));
            item.put("title", row.getString("title"));
            item.put("pending", ((Number) row.get("pending")).intValue());
            rows.add(item);
        }
        return rows;
    }

    private static int number(Document event, String field) {
        return event.get(field) instanceof Number number ? number.intValue() : 0;
    }

    private static boolean upcoming(Document event, Instant now) {
        return EventStatus.PUBLISHED.name().equals(event.getString("status")) && Refs.plain(event.get("startAt")) instanceof Instant startAt && startAt.isAfter(now);
    }

    /**
     * The numbers for the club's managers. "Events" are the ones that reached campus (published or completed);
     * drafts and events in review are reported separately as the pipeline.
     */
    private Map<String, Object> insights(ObjectId clubId, long memberCount, Instant now) {
        List<Document> hosted;
        long pipeline;
        try (Parallel parallel = new Parallel()) {
            Query hostedQuery = Query.query(Criteria.where("club").is(clubId).and("status").in(HOSTED)).with(Sort.by(Sort.Direction.DESC, "startAt"));
            for (String field : List.of("title", "startAt", "endAt", "status", "registeredCount", "maxParticipants", "waitlistCount")) {
                hostedQuery.fields().include(field);
            }
            Supplier<List<Document>> hostedEvents = parallel.fork(() -> mongo.find(hostedQuery, Document.class, "events"));
            Supplier<Long> inPipeline = parallel.fork(() -> mongo.count(Query.query(Criteria.where("club").is(clubId).and("status").in(PIPELINE)), Event.class));
            hosted = hostedEvents.get();
            pipeline = inPipeline.get();
        }

        Map<String, Integer> attended = checkIns.attendedCountsByEvent(hosted.stream().map(event -> event.getObjectId("_id")).toList());
        ToIntFunction<Document> attendedOf = event -> attended.getOrDefault(event.getObjectId("_id").toHexString(), 0);
        // Attendance rate over completed events: people checked in at the door out of those registered.
        List<Document> completed = hosted.stream().filter(event -> EventStatus.COMPLETED.name().equals(event.getString("status")) && number(event, "registeredCount") != 0).toList();
        int completedRegistered = completed.stream().mapToInt(event -> number(event, "registeredCount")).sum();
        int completedAttended = completed.stream().mapToInt(attendedOf).sum();

        int totalRegistrations = hosted.stream().mapToInt(event -> number(event, "registeredCount")).sum();
        List<Document> capped = hosted.stream().filter(event -> number(event, "maxParticipants") != 0).toList();
        int seats = capped.stream().mapToInt(event -> number(event, "maxParticipants")).sum();
        int seatsTaken = capped.stream().mapToInt(event -> number(event, "registeredCount")).sum();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("totalMembers", memberCount);
        view.put("totalEvents", hosted.size());
        view.put("eventsInPipeline", pipeline);
        view.put("totalRegistrations", totalRegistrations);
        view.put("upcomingEvents", hosted.stream().filter(event -> upcoming(event, now)).count());
        view.put("completedEvents", hosted.stream().filter(event -> EventStatus.COMPLETED.name().equals(event.getString("status"))).count());
        view.put("averageParticipation", hosted.isEmpty() ? 0 : Math.round((double) totalRegistrations / hosted.size() * 10) / 10.0);
        // Share of offered seats that were taken, across events with a participant limit.
        view.put("seatFillRate", seats != 0 ? Math.round((double) seatsTaken / seats * 100) : null);
        view.put("waitlisted", hosted.stream().mapToInt(event -> number(event, "waitlistCount")).sum());
        view.put("attendanceRate", completedRegistered != 0 ? Math.round((double) completedAttended / completedRegistered * 100) : null);
        view.put("totalAttended", hosted.stream().mapToInt(attendedOf).sum());
        view.put("eventWise", hosted.stream().limit(INSIGHT_EVENTS).map(event -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_id", event.getObjectId("_id").toHexString());
            row.put("title", event.getString("title"));
            row.put("startAt", Refs.plain(event.get("startAt")));
            row.put("status", event.getString("status"));
            row.put("upcoming", upcoming(event, now));
            row.put("registered", number(event, "registeredCount"));
            row.put("capacity", number(event, "maxParticipants") != 0 ? number(event, "maxParticipants") : null);
            row.put("waitlist", number(event, "waitlistCount"));
            row.put("attended", attendedOf.applyAsInt(event));
            return row;
        }).toList());
        return view;
    }

    /** The club's recruitment drive in progress, with what needs doing next for each position. */
    private Map<String, Object> recruitmentSummary(ObjectId clubId, Instant now) {
        RecruitmentDrive drive = mongo.findOne(Query.query(Criteria.where("club").is(clubId).and("status").in(DRIVE_IN_PROGRESS))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")), RecruitmentDrive.class);
        if (drive == null) {
            return null;
        }
        Query query = Query.query(Criteria.where("drive").is(new ObjectId(drive.getId())).and("status").in(IN_PLAY));
        query.fields().include("position").include("status").include("pendingOutcome").include("slots");
        List<RecruitmentApplication> applications = mongo.find(query, RecruitmentApplication.class);

        List<Map<String, Object>> positions = new ArrayList<>();
        for (RecruitmentPosition position : drive.getPositions()) {
            List<RecruitmentApplication> mine = applications.stream().filter(application -> Objects.equals(application.getPosition(), position.getId())).toList();
            RecruitmentRound round = position.getRounds().isEmpty() ? null : position.getRounds().getLast();
            List<RecruitmentApplication> active = mine.stream().filter(application -> ACTIVE.contains(application.getStatus())).toList();
            long undecided = round != null && round.getStatus() != RoundStatus.RESULTS_PUBLISHED
                    ? active.stream().filter(application -> application.getPendingOutcome() == null
                            || !Objects.equals(application.getPendingOutcome().getRound(), round.getId())
                            || application.getPendingOutcome().getOutcome() == null).count()
                    : 0;
            InterviewSlot next = round == null ? null : active.stream()
                    .flatMap(application -> application.getSlots().stream())
                    .filter(slot -> Objects.equals(slot.getRound(), round.getId()) && slot.getStartAt() != null && slot.getStartAt().isAfter(now))
                    .min(Comparator.comparing(InterviewSlot::getStartAt))
                    .orElse(null);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_id", position.getId());
            row.put("title", position.getTitle());
            row.put("finalizedAt", position.getFinalizedAt());
            row.put("applications", active.size());
            row.put("pendingOffers", mine.stream().filter(application -> application.getStatus() == ApplicationStatus.OFFERED).count());
            Map<String, Object> roundView = null;
            if (round != null) {
                roundView = new LinkedHashMap<>();
                roundView.put("_id", round.getId());
                roundView.put("name", round.getName());
                roundView.put("mode", round.getMode());
                roundView.put("status", round.getStatus());
                roundView.put("undecided", undecided);
            }
            row.put("round", roundView);
            row.put("nextInterviewAt", next == null ? null : next.getStartAt());
            positions.add(row);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", drive.getId());
        view.put("title", drive.getTitle());
        view.put("status", drive.getStatus());
        view.put("phase", DrivePhases.phaseOf(drive, now));
        view.put("reviewComment", drive.getReviewComment());
        view.put("applicationEnd", drive.getApplicationEnd());
        view.put("applications", applications.stream().filter(application -> ACTIVE.contains(application.getStatus())).count());
        view.put("positions", positions);
        return view;
    }
}
