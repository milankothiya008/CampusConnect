package com.campusconnect.feature.dashboard;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Parallel;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubCreationRequest;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubRequestStatus;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RevisionStatus;
import com.campusconnect.feature.admin.AdminService;
import com.campusconnect.feature.club.DocumentViews;
import com.campusconnect.feature.event.EventViews;
import com.campusconnect.feature.notification.NotificationInbox;
import com.campusconnect.feature.recruitment.drive.DriveService;
import com.campusconnect.feature.recruitment.dto.DriveViews.ReviewItem;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Everyone's home screen (services/DashboardService.js): the admin's overview, the faculty mentor's review queues, or
 * the student's schedule, club workspaces and recommendations; plus the unread count and the campus events. The
 * independent reads of each part run at once ({@link Parallel}), as the Node service's Promise.all did.
 */
@Service
public class DashboardService {

    private final MongoOperations mongo;
    private final Refs refs;
    private final DocumentViews documents;
    private final NotificationInbox inbox;
    private final AdminService admin;
    private final EventViews events;
    private final DriveService drives;
    private final EventCards cards;
    private final StudentDashboard students;

    public DashboardService(MongoOperations mongo, Refs refs, DocumentViews documents, NotificationInbox inbox, AdminService admin, EventViews events,
            DriveService drives, EventCards cards, StudentDashboard students) {
        this.mongo = mongo;
        this.refs = refs;
        this.documents = documents;
        this.inbox = inbox;
        this.admin = admin;
        this.events = events;
        this.drives = drives;
        this.cards = cards;
        this.students = students;
    }

    public Map<String, Object> getDashboard(AuthUser actor) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("role", actor.globalRole());
        try (Parallel parallel = new Parallel()) {
            Supplier<Long> unread = parallel.fork(() -> inbox.unreadCount(actor));
            if (actor.isAdmin()) {
                Supplier<List<Map<String, Object>>> campusEvents = parallel.fork(() -> campusEvents(actor));
                Map<String, Object> adminView = adminDashboard(actor);
                view.put("unreadNotifications", unread.get());
                view.put("campusEvents", campusEvents.get());
                view.put("admin", adminView);
                return view;
            }
            if (actor.isFaculty()) {
                Supplier<List<Map<String, Object>>> campusEvents = parallel.fork(() -> campusEvents(actor));
                Map<String, Object> faculty = facultyDashboard(actor);
                // Events of the faculty's own clubs are already listed in their section.
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> own = (List<Map<String, Object>>) faculty.get("upcomingEvents");
                Set<String> ownIds = new HashSet<>(own.stream().map(event -> String.valueOf(event.get("_id"))).toList());
                view.put("unreadNotifications", unread.get());
                view.put("campusEvents", campusEvents.get().stream().filter(event -> !ownIds.contains(String.valueOf(event.get("_id")))).toList());
                view.put("faculty", faculty);
                return view;
            }
            Map<String, Object> student = students.build(actor);
            view.put("unreadNotifications", unread.get());
            view.put("student", student);
            return view;
        }
    }

    /** The next six events on campus, as the events feed shows them. */
    private List<Map<String, Object>> campusEvents(AuthUser actor) {
        return events.listEvents(actor, null, "6", "upcoming", null, null, null, null, null).items();
    }

    private Map<String, Object> adminDashboard(AuthUser actor) {
        try (Parallel parallel = new Parallel()) {
            Supplier<AdminService.Stats> stats = parallel.fork(() -> admin.getStats(actor));
            Supplier<List<Map<String, Object>>> awaitingApproval = parallel.fork(this::awaitingApproval);
            Supplier<List<Map<String, Object>>> recentClubs = parallel.fork(this::recentClubs);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("stats", stats.get());
            view.put("awaitingApproval", awaitingApproval.get());
            view.put("recentClubs", recentClubs.get());
            return view;
        }
    }

    /** Club proposals a faculty member has verified, waiting for the admin (longest waiting first). */
    private List<Map<String, Object>> awaitingApproval() {
        List<ClubCreationRequest> requests = mongo.find(Query.query(Criteria.where("status").is(ClubRequestStatus.FACULTY_VERIFIED.name()))
                .with(Sort.by("verifiedAt")).limit(20), ClubCreationRequest.class);
        Map<String, Map<String, Object>> people = refs.users(requests.stream()
                .flatMap(request -> Stream.of(request.getRequester(), request.getVerifiedBy())).filter(Objects::nonNull).toList(), "name", "email");
        return requests.stream().map(request -> {
            Map<String, Object> row = documents.of(request);
            row.put("requester", DocumentViews.row(people, request.getRequester()));
            row.put("verifiedBy", DocumentViews.row(people, request.getVerifiedBy()));
            return row;
        }).toList();
    }

    /** The newest clubs, with their mentor and president. */
    private List<Map<String, Object>> recentClubs() {
        List<Club> clubs = mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(8), Club.class);
        Map<String, Map<String, Object>> leaders = refs.users(clubs.stream()
                .flatMap(club -> Stream.of(club.getMentor(), club.getPresident())).filter(Objects::nonNull).toList(), "name");
        return clubs.stream().map(club -> {
            Map<String, Object> row = documents.of(club);
            row.put("mentor", DocumentViews.row(leaders, club.getMentor()));
            row.put("president", DocumentViews.row(leaders, club.getPresident()));
            return row;
        }).toList();
    }

    private Map<String, Object> facultyDashboard(AuthUser actor) {
        ObjectId me = new ObjectId(actor.id());
        List<Club> mentored = mongo.find(Query.query(Criteria.where("mentor").is(me)).with(Sort.by("name")), Club.class);
        List<ObjectId> clubIds = mentored.stream().map(club -> new ObjectId(club.getId())).toList();

        try (Parallel parallel = new Parallel()) {
            Supplier<Map<String, Map<String, Object>>> presidents = parallel.fork(() -> refs.users(mentored.stream().map(Club::getPresident).filter(Objects::nonNull).toList(),
                    "name", "email"));
            Supplier<List<Map<String, Object>>> requestsToReview = parallel.fork(() -> requestsToReview(me));
            Supplier<List<Map<String, Object>>> verifiedRequests = parallel.fork(() -> verifiedRequests(me));
            Supplier<List<Map<String, Object>>> eventsToReview = clubIds.isEmpty() ? List::of : parallel.fork(() -> cards.find(Query.query(Criteria.where("club").in(clubIds)
                    .orOperator(Criteria.where("status").is(EventStatus.PENDING_APPROVAL.name()), Criteria.where("revision.status").is(RevisionStatus.PENDING_APPROVAL.name())))
                    .with(Sort.by("submittedAt")), EventCards.Populate.CLUB_AND_VENUE, "submittedAt", "revision.status", "revision.fields", "revision.requestedAt"));
            Supplier<List<Map<String, Object>>> upcomingEvents = clubIds.isEmpty() ? List::of : parallel.fork(() -> cards.find(Query.query(Criteria.where("club").in(clubIds)
                    .and("status").is(EventStatus.PUBLISHED.name()).and("endAt").gt(Instant.now())).with(Sort.by("startAt")).limit(10), EventCards.Populate.CLUB_AND_VENUE));
            Supplier<Map<String, Long>> memberCounts = parallel.fork(() -> memberCounts(clubIds));
            Supplier<List<ReviewItem>> drivesToReview = parallel.fork(() -> drives.listDrivesToReview(actor));

            Map<String, Long> counts = memberCounts.get();
            Map<String, Map<String, Object>> presidentRows = presidents.get();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("mentoredClubs", mentored.stream().map(club -> {
                Map<String, Object> row = documents.of(club);
                row.put("president", DocumentViews.row(presidentRows, club.getPresident()));
                row.put("memberCount", counts.getOrDefault(club.getId(), 0L));
                return row;
            }).toList());
            view.put("clubsAwaitingPresident", mentored.stream().filter(club -> club.getStatus() == ClubStatus.APPROVED && club.getPresident() == null)
                    .map(club -> {
                        Map<String, Object> row = documents.of(club);
                        row.put("president", null);
                        return row;
                    })
                    .toList());
            view.put("requestsToReview", requestsToReview.get());
            view.put("verifiedRequests", verifiedRequests.get());
            view.put("eventsToReview", eventsToReview.get());
            view.put("drivesToReview", drivesToReview.get());
            view.put("upcomingEvents", upcomingEvents.get());
            return view;
        }
    }

    /** Club proposals naming this faculty member as mentor, or naming no one (oldest first). */
    private List<Map<String, Object>> requestsToReview(ObjectId me) {
        List<ClubCreationRequest> requests = mongo.find(Query.query(Criteria.where("status").is(ClubRequestStatus.PENDING_FACULTY_REVIEW.name())
                .orOperator(Criteria.where("proposedMentor").is(me), Criteria.where("proposedMentor").is(null))).with(Sort.by("createdAt")).limit(20), ClubCreationRequest.class);
        Map<String, Map<String, Object>> requesters = refs.users(requests.stream().map(ClubCreationRequest::getRequester).filter(Objects::nonNull).toList(),
                "name", "email", "departmentCode");
        return requests.stream().map(request -> {
            Map<String, Object> row = documents.of(request);
            row.put("requester", DocumentViews.row(requesters, request.getRequester()));
            return row;
        }).toList();
    }

    /** Proposals this faculty member verified that are still waiting for the admin. */
    private List<Map<String, Object>> verifiedRequests(ObjectId me) {
        Query query = Query.query(Criteria.where("verifiedBy").is(me).and("status").is(ClubRequestStatus.FACULTY_VERIFIED.name())).limit(10);
        query.fields().include("name").include("status").include("verifiedAt");
        return mongo.find(query, Document.class, "clubcreationrequests").stream().map(row -> Refs.shape(row, "name", "status", "verifiedAt")).toList();
    }

    /** Approved members per club id. */
    private Map<String, Long> memberCounts(List<ObjectId> clubIds) {
        Map<String, Long> counts = new HashMap<>();
        if (clubIds.isEmpty()) {
            return counts;
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("club").in(clubIds).and("status").is(MembershipStatus.APPROVED.name())),
                Aggregation.group("club").count().as("count"));
        for (Document row : mongo.aggregate(aggregation, mongo.getCollectionName(ClubMembership.class), Document.class)) {
            counts.put(row.getObjectId("_id").toHexString(), ((Number) row.get("count")).longValue());
        }
        return counts;
    }
}
