package com.campusconnect.feature.dashboard;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Parallel;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.recruitment.application.ApplicationService;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.MyApplicationRow;
import com.campusconnect.feature.registration.RegistrationService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The student dashboard (DashboardService.studentDashboard). Every event appears in exactly one place: the student's
 * own schedule (registered), their club workspace (clubs they help run) or the recommendations (everything else they
 * can still register for).
 */
@Component
public class StudentDashboard {

    /** populate("club", ...) of the memberships. */
    static final String[] CLUB_FIELDS = {"name", "logo", "category", "status", "president", "mentor", "allDepartments", "departmentCodes", "roles"};
    static final String[] REQUEST_FIELDS = {"name", "status", "updatedAt", "reviewComment", "rejectionReason", "club"};
    static final List<ApplicationStatus> ACTIVE_APPLICATIONS = List.of(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS);
    static final Duration RECENT_APPLICATIONS = Duration.ofDays(30);

    private final MongoOperations mongo;
    private final RegistrationService registrations;
    private final ApplicationService applications;
    private final ClubWorkspaces workspaces;
    private final EventCards cards;

    public StudentDashboard(MongoOperations mongo, RegistrationService registrations, ApplicationService applications, ClubWorkspaces workspaces, EventCards cards) {
        this.mongo = mongo;
        this.registrations = registrations;
        this.applications = applications;
        this.workspaces = workspaces;
        this.cards = cards;
    }

    /** An approved membership with its club: the entity (roles, status) and the populated view. */
    record Membership(ClubMembership membership, Club club, Map<String, Object> clubView) {
    }

    public Map<String, Object> build(AuthUser actor) {
        Instant now = Instant.now();
        ObjectId me = new ObjectId(actor.id());
        try (Parallel parallel = new Parallel()) {
            // Queries that don't depend on the student's clubs start now, alongside the memberships lookup.
            Supplier<List<Map<String, Object>>> upcomingTask = parallel.fork(() -> registrations.getMyRegistrations(actor, "upcoming", "true"));
            Supplier<List<Map<String, Object>>> pastTask = parallel.fork(() -> registrations.getMyRegistrations(actor, "past", null));
            Supplier<List<Map<String, Object>>> clubRequests = parallel.fork(() -> clubRequests(me));
            Supplier<List<Notification>> recentNotifications = parallel.fork(() -> mongo.find(Query.query(Criteria.where("user").is(me))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(5), Notification.class));
            Supplier<List<MyApplicationRow>> applicationsTask = parallel.fork(() -> applications.myApplications(actor));

            List<Membership> memberships = memberships(me);
            // A workspace for every club where the student holds some authority.
            List<Membership> officerships = memberships.stream()
                    .filter(row -> !ClubRoles.permissionsFor(row.club(), row.membership().getRole()).isEmpty() && row.club().getStatus() == ClubStatus.ACTIVE)
                    .toList();
            List<Supplier<Map<String, Object>>> workspaceTasks = officerships.stream()
                    .map(row -> parallel.fork(() -> workspaces.workspace(row.membership(), row.club(), row.clubView())))
                    .toList();

            List<Map<String, Object>> upcomingAll = upcomingTask.get();
            List<Map<String, Object>> pastRegistrations = new ArrayList<>(pastTask.get());
            List<Map<String, Object>> upcomingRegistrations = withStatus(upcomingAll, RegistrationStatus.REGISTERED);
            List<Map<String, Object>> waitlistedRegistrations = withStatus(upcomingAll, RegistrationStatus.WAITLISTED);
            // Events the student holds or queues for a seat at are never recommended again.
            Set<String> registeredIds = new HashSet<>();
            Stream.concat(upcomingAll.stream(), pastRegistrations.stream()).forEach(row -> registeredIds.add(eventId(row)));

            // Club workspaces manage their own events; events the student is attending live in their schedule.
            List<Map<String, Object>> clubWorkspaces = workspaceTasks.stream().map(Supplier::get).toList();
            for (Map<String, Object> workspace : clubWorkspaces) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> upcoming = (List<Map<String, Object>>) workspace.get("upcoming");
                workspace.put("upcoming", upcoming.stream().filter(event -> !registeredIds.contains(String.valueOf(event.get("_id")))).toList());
            }
            List<ObjectId> managedClubIds = officerships.stream().map(row -> new ObjectId(row.club().getId())).toList();
            Set<String> myClubIds = new HashSet<>(memberships.stream().map(row -> row.club().getId()).toList());

            List<ObjectId> pastEventIds = pastRegistrations.stream().map(StudentDashboard::eventId).filter(ObjectId::isValid).map(ObjectId::new).toList();
            Supplier<List<Map<String, Object>>> openEvents = parallel.fork(() -> openEvents(actor, now, registeredIds, managedClubIds));
            Supplier<Set<String>> withResults = parallel.fork(() -> eventsWithPublishedResults(pastEventIds));

            // Events from the student's own clubs first, then by date.
            List<Map<String, Object>> recommended = openEvents.get().stream()
                    .filter(event -> "OPEN".equals(event.get("registrationState")))
                    .map(event -> {
                        Map<String, Object> row = new LinkedHashMap<>(event);
                        row.put("fromMyClub", event.get("club") instanceof Map<?, ?> club && myClubIds.contains(String.valueOf(club.get("_id"))));
                        return row;
                    })
                    .sorted(Comparator.<Map<String, Object>>comparingInt(event -> Boolean.TRUE.equals(event.get("fromMyClub")) ? 0 : 1)
                            .thenComparing(event -> (Instant) event.get("startAt"), Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(6)
                    .toList();

            List<MyApplicationRow> myApplications = applicationsTask.get();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("upcoming", upcomingRegistrations.size());
            stats.put("attended", pastRegistrations.stream().filter(row -> EventStatus.COMPLETED.name().equals(eventOf(row).get("status"))).count());
            stats.put("clubs", memberships.size());
            stats.put("applications", myApplications.stream().filter(application -> ACTIVE_APPLICATIONS.contains(application.status())).count());
            stats.put("waitlisted", waitlistedRegistrations.size());

            Set<String> resultEvents = withResults.get();
            pastRegistrations.sort(Comparator.comparing((Map<String, Object> row) -> eventOf(row).get("startAt") instanceof Instant startAt ? startAt : Instant.MIN).reversed());
            List<Map<String, Object>> past = pastRegistrations.stream().limit(6).map(row -> {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.put("hasResults", resultEvents.contains(eventId(row)));
                return copy;
            }).toList();

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("stats", stats);
            view.put("upcomingRegistrations", upcomingRegistrations.stream().limit(6).toList());
            view.put("waitlistedRegistrations", waitlistedRegistrations.stream().limit(6).toList());
            view.put("pastRegistrations", past);
            view.put("memberships", memberships.stream().map(row -> {
                Map<String, Object> membership = new LinkedHashMap<>();
                membership.put("_id", row.membership().getId());
                membership.put("role", row.membership().getRole());
                membership.put("status", row.membership().getStatus());
                membership.put("club", row.clubView());
                return membership;
            }).toList());
            view.put("clubWorkspaces", clubWorkspaces);
            // Applications still in play, plus results from the last month.
            view.put("applications", myApplications.stream()
                    .filter(application -> ACTIVE_APPLICATIONS.contains(application.status())
                            || (application.createdAt() != null && Duration.between(application.createdAt(), now).compareTo(RECENT_APPLICATIONS) < 0))
                    .limit(6)
                    .toList());
            view.put("clubRequests", clubRequests.get());
            view.put("recommended", recommended);
            view.put("recentNotifications", recentNotifications.get());
            return view;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventOf(Map<String, Object> registration) {
        return registration.get("event") instanceof Map<?, ?> event ? (Map<String, Object>) event : Map.of();
    }

    private static String eventId(Map<String, Object> registration) {
        return String.valueOf(eventOf(registration).get("_id"));
    }

    private static List<Map<String, Object>> withStatus(List<Map<String, Object>> registrations, RegistrationStatus status) {
        return registrations.stream().filter(row -> status.name().equals(String.valueOf(row.get("status")))).toList();
    }

    /** Approved memberships whose club still exists, with the club read once (populate "club", CLUB_FIELDS). */
    private List<Membership> memberships(ObjectId me) {
        List<ClubMembership> memberships = mongo.find(Query.query(Criteria.where("user").is(me).and("status").is(MembershipStatus.APPROVED.name())), ClubMembership.class);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Query clubQuery = Query.query(Criteria.where("_id").in(memberships.stream().map(membership -> new ObjectId(membership.getClub())).toList()));
        for (String field : CLUB_FIELDS) {
            clubQuery.fields().include(field);
        }
        Map<String, Document> clubs = new HashMap<>();
        mongo.find(clubQuery, Document.class, "clubs").forEach(club -> clubs.put(club.getObjectId("_id").toHexString(), club));
        List<Membership> rows = new ArrayList<>();
        for (ClubMembership membership : memberships) {
            Document club = clubs.get(membership.getClub());
            if (club != null) {
                rows.add(new Membership(membership, mongo.getConverter().read(Club.class, club), Refs.shape(club, CLUB_FIELDS)));
            }
        }
        return rows;
    }

    /** The student's own club proposals (as requester or founding member), most recently updated first. */
    private List<Map<String, Object>> clubRequests(ObjectId me) {
        Query query = Query.query(new Criteria().orOperator(Criteria.where("requester").is(me), Criteria.where("foundingMembers").is(me)))
                .with(Sort.by(Sort.Direction.DESC, "updatedAt")).limit(5);
        for (String field : REQUEST_FIELDS) {
            query.fields().include(field);
        }
        return mongo.find(query, Document.class, "clubcreationrequests").stream().map(row -> Refs.shape(row, REQUEST_FIELDS)).toList();
    }

    /** Published events the student could still register for, outside the clubs they run. */
    private List<Map<String, Object>> openEvents(AuthUser actor, Instant now, Set<String> registeredIds, List<ObjectId> managedClubIds) {
        Criteria open = Criteria.where("status").is(EventStatus.PUBLISHED.name())
                .and("startAt").gt(now)
                .and("registrationClosed").is(false)
                .and("registrationEnd").gte(now)
                .and("_id").nin(registeredIds.stream().filter(ObjectId::isValid).map(ObjectId::new).toList())
                .and("club").nin(managedClubIds)
                .andOperator(eligibleFor("departments", actor.departmentCode()), eligibleFor("batches", actor.batchCode()));
        return cards.find(Query.query(open).with(Sort.by("startAt")).limit(12), EventCards.Populate.CLUB_AND_VENUE, "shortDescription", "eligibility");
    }

    /** Open to everyone ({@code []}) or to this department or batch. */
    private static Criteria eligibleFor(String field, String value) {
        return new Criteria().orOperator(Criteria.where("eligibility." + field).size(0), Criteria.where("eligibility." + field).is(value));
    }

    private Set<String> eventsWithPublishedResults(List<ObjectId> eventIds) {
        Set<String> ids = new HashSet<>();
        if (eventIds.isEmpty()) {
            return ids;
        }
        Query query = Query.query(Criteria.where("event").in(eventIds).and("status").is(ResultStatus.PUBLISHED.name()));
        query.fields().include("event");
        mongo.find(query, Document.class, "eventresults").forEach(row -> ids.add(row.getObjectId("event").toHexString()));
        return ids;
    }
}
