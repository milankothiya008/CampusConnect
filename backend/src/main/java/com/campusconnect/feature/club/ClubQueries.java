package com.campusconnect.feature.club;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Page;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.feature.subscription.SubscriptionService;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** The read side of services/ClubService.js: the directory, a club's page, "my clubs" and member lists. */
@Service
public class ClubQueries {

    static final List<String> MEMBER_FIELDS = List.of("name", "email", "phone", "departmentCode", "batchCode", "avatar");

    /** What the viewer is to the club (viewerSummary plus the bell and member-list flags). */
    public record ClubViewer(
            String role,
            String roleName,
            List<String> permissions,
            @JsonProperty("isMember") boolean isMember,
            @JsonProperty("isMentor") boolean isMentor,
            @JsonProperty("isAdmin") boolean isAdmin,
            MembershipStatus membershipStatus,
            List<Map<String, Object>> applications,
            boolean subscribed,
            boolean canSeeMembers) {
    }

    /** The signed-in user's memberships and the clubs they mentor. */
    public record MyClubs(List<Map<String, Object>> memberships, List<Map<String, Object>> mentored) {
    }

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final Refs refs;
    private final DocumentViews views;
    private final ClubListing listing;
    private final ClubDirectory directory;
    private final OpenDrives openDrives;
    private final SubscriptionService subscriptions;

    public ClubQueries(MongoOperations mongo, ClubAccess access, Refs refs, DocumentViews views, ClubListing listing, ClubDirectory directory,
            OpenDrives openDrives, SubscriptionService subscriptions) {
        this.mongo = mongo;
        this.access = access;
        this.refs = refs;
        this.views = views;
        this.listing = listing;
        this.directory = directory;
        this.openDrives = openDrives;
        this.subscriptions = subscriptions;
    }

    // ---------------------------------------------------------------- directory

    /** Only the admin browses clubs that are not active; everyone else sees the (shared, cached) public directory. */
    public Page<Map<String, Object>> listClubs(AuthUser actor, String page, String limit, String status, String search, String category, String department) {
        Pagination pagination = Pagination.of(page, limit, 12);
        if (ClubAccess.isAdmin(actor)) {
            String wanted = status != null && !status.isEmpty() ? ("ALL".equals(status) ? null : status.toUpperCase(Locale.ROOT)) : ClubStatus.ACTIVE.name();
            ClubListing.Loaded loaded = listing.load(ClubListing.filter(wanted, search, category, department), pagination);
            return Page.of(loaded.items(), pagination, loaded.total());
        }
        ClubDirectory.DirectoryPage cached = directory.page(ClubDirectory.key(pagination, search, category, department), pagination, search, category, department);
        // Recruitment drives open and close by the clock, so that part is worked out fresh.
        Instant now = Instant.now();
        List<Map<String, Object>> items = cached.items().stream().map(item -> withLiveRecruiting(item, now)).toList();
        return Page.of(items, pagination, cached.total());
    }

    private static Map<String, Object> withLiveRecruiting(Map<String, Object> item, Instant now) {
        Map<String, Object> copy = new LinkedHashMap<>(item);
        if (item.get("recruiting") instanceof Map<?, ?> drive) {
            Instant end = OpenDrives.instant(drive.get("applicationEnd"));
            if (end == null || !end.isAfter(now)) {
                copy.put("recruiting", null);
            } else {
                Map<String, Object> live = new LinkedHashMap<>();
                drive.forEach((key, value) -> live.put(String.valueOf(key), value));
                Instant start = OpenDrives.instant(drive.get("applicationStart"));
                live.put("open", start == null || !start.isAfter(now));
                copy.put("recruiting", live);
            }
        }
        return copy;
    }

    // ---------------------------------------------------------------- one club

    /** The club page. Admins may open any club (to change its status or mentor), but get no club permissions from it. */
    public Map<String, Object> getClub(AuthUser actor, String clubId) {
        ClubContext context = access.context(actor, clubId);
        Club club = context.club();
        if (club.getStatus() != ClubStatus.ACTIVE && !context.isAdmin() && !context.isMentor() && !context.isMember()) {
            throw AppException.notFound("Club not found");
        }
        boolean insider = context.isMember() || context.isMentor() || context.isAdmin();
        Set<String> people = new LinkedHashSet<>();
        people.add(club.getPresident());
        people.add(club.getMentor());
        people.remove(null);
        Map<String, Map<String, Object>> users = refs.users(people, "name", "email", "departmentCode", "batchCode", "avatar", "phone");
        Map<String, Object> president = DocumentViews.pick(club.getPresident() == null ? null : users.get(club.getPresident()),
                List.of("name", "email", "departmentCode", "batchCode", "avatar", "phone"));
        Map<String, Object> mentor = DocumentViews.pick(club.getMentor() == null ? null : users.get(club.getMentor()),
                List.of("name", "email", "departmentCode", "avatar", "phone"));

        long memberCount = listing.memberCounts(List.of(club.getId())).getOrDefault(club.getId(), 0L);
        Map<String, Object> recruiting = openDrives.byClub(List.of(club.getId())).get(club.getId());
        long upcomingEvents = mongo.count(Query.query(Criteria.where("club").is(new ObjectId(club.getId()))
                .and("status").is(EventStatus.PUBLISHED.name()).and("startAt").gte(Instant.now())), "events");
        int followerCount = subscriptions.followerCount(club.getId());
        boolean subscribed = actor != null && subscriptions.isSubscribed(actor.id(), club.getId());
        boolean canSeeMembers = insider || access.canSeeMemberDirectory(actor);
        List<Map<String, Object>> applications = actor != null && recruiting != null ? applications(actor, (String) recruiting.get("_id")) : List.of();

        Map<String, Object> view = views.of(club);
        if (!insider) {
            view.remove("statusNote");
        }
        // Contact numbers: the mentor's for club members anywhere, faculty and the admin; the president's like any
        // member's number (club members, mentors, the admin).
        if (mentor != null && !(actor != null && access.canSeeFacultyContacts(actor))) {
            mentor.remove("phone");
        }
        if (president != null && !canSeeMembers) {
            president.remove("phone");
        }
        view.put("president", president);
        view.put("mentor", mentor);
        view.put("memberCount", memberCount);
        view.put("followerCount", followerCount);
        view.put("upcomingEvents", upcomingEvents);
        view.put("recruiting", recruiting);
        view.put("viewer", actor == null ? null : new ClubViewer(context.role(), context.roleName(), context.permissions(), context.isMember(), context.isMentor(),
                context.isAdmin(), context.isMember() ? MembershipStatus.APPROVED : null, applications, subscribed, canSeeMembers));
        return view;
    }

    /** The viewer's applications (one per role) to the club's current drive. */
    private List<Map<String, Object>> applications(AuthUser actor, String driveId) {
        Query query = Query.query(Criteria.where("drive").is(driveId).and("applicant").is(actor.id()).and("status").ne(ApplicationStatus.WITHDRAWN));
        query.fields().include("status", "position");
        return mongo.find(query, RecruitmentApplication.class).stream().map(application -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_id", application.getId());
            row.put("status", application.getStatus());
            row.put("position", application.getPosition());
            return row;
        }).toList();
    }

    // ---------------------------------------------------------------- my clubs

    /** Memberships (newest change first) with their clubs, and the clubs the user mentors with member counts. */
    public MyClubs getMyClubs(AuthUser actor) {
        List<ClubMembership> memberships = mongo.find(Query.query(Criteria.where("user").is(actor.id()).and("status").is(MembershipStatus.APPROVED))
                .with(Sort.by(Sort.Direction.DESC, "updatedAt")), ClubMembership.class);
        Map<String, Club> memberOf = memberships.isEmpty() ? Map.of() : mongo.find(Query.query(Criteria.where("_id").in(memberships.stream().map(ClubMembership::getClub).toList())), Club.class)
                .stream().collect(Collectors.toMap(Club::getId, Function.identity()));
        List<Club> mentored = mongo.find(Query.query(Criteria.where("mentor").is(actor.id())).with(Sort.by("name")), Club.class);

        Set<String> people = new LinkedHashSet<>();
        memberOf.values().forEach(club -> people.add(club.getPresident()));
        mentored.forEach(club -> {
            people.add(club.getPresident());
            people.add(club.getMentor());
        });
        people.remove(null);
        Map<String, Map<String, Object>> users = refs.users(people, "name", "email", "departmentCode", "batchCode");

        List<Map<String, Object>> membershipRows = new ArrayList<>();
        for (ClubMembership membership : memberships) {
            Club club = memberOf.get(membership.getClub());
            if (club == null) {
                continue;
            }
            Map<String, Object> clubView = views.of(club);
            clubView.put("president", DocumentViews.pick(club.getPresident() == null ? null : users.get(club.getPresident()), List.of("name")));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_id", membership.getId());
            row.put("role", membership.getRole());
            row.put("roleName", ClubRoles.roleName(club, membership.getRole()));
            row.put("status", membership.getStatus());
            row.put("joinedAt", membership.getJoinedAt());
            row.put("permissions", ClubRoles.permissionsFor(club, membership.getRole()));
            row.put("club", clubView);
            membershipRows.add(row);
        }

        List<String> mentoredIds = mentored.stream().map(Club::getId).toList();
        Map<String, Long> counts = listing.memberCounts(mentoredIds);
        Map<String, Map<String, Object>> open = mentored.isEmpty() ? Map.of() : openDrives.byClub(mentoredIds);
        List<Map<String, Object>> mentoredRows = mentored.stream().map(club -> {
            Map<String, Object> view = views.of(club);
            view.put("president", DocumentViews.pick(club.getPresident() == null ? null : users.get(club.getPresident()), List.of("name", "email", "departmentCode", "batchCode")));
            view.put("mentor", DocumentViews.pick(club.getMentor() == null ? null : users.get(club.getMentor()), List.of("name", "email", "departmentCode")));
            view.put("memberCount", counts.getOrDefault(club.getId(), 0L));
            view.put("recruiting", open.get(club.getId()));
            return view;
        }).toList();
        return new MyClubs(membershipRows, mentoredRows);
    }

    // ---------------------------------------------------------------- members

    /**
     * Members of any club, every club mentor and the admin can reach any club's members (and their mobile numbers)
     * to coordinate; students outside all clubs cannot. Officers first, then members by joining date.
     */
    public List<Map<String, Object>> listClubMembers(AuthUser actor, String clubId) {
        ClubContext context = access.context(actor, clubId);
        Club club = context.club();
        boolean insider = context.isMentor() || context.isMember() || context.isAdmin();
        if (!insider && !access.canSeeMemberDirectory(actor)) {
            throw AppException.forbidden("Member lists are open to club members, mentors and the admin", ErrorCode.FORBIDDEN);
        }
        if (!insider && club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.notFound("Club not found");
        }
        List<ClubMembership> members = mongo.find(Query.query(Criteria.where("club").is(club.getId()).and("status").is(MembershipStatus.APPROVED))
                .with(Sort.by("joinedAt")), ClubMembership.class);
        Map<String, Map<String, Object>> users = refs.users(members.stream().map(ClubMembership::getUser).toList(), MEMBER_FIELDS.toArray(String[]::new));
        return members.stream()
                .filter(member -> users.containsKey(member.getUser()))
                .sorted(Comparator.comparingInt(member -> ClubRoles.roleRank(club, member.getRole())))
                .map(member -> {
                    Map<String, Object> view = views.of(member);
                    view.put("user", DocumentViews.pick(users.get(member.getUser()), MEMBER_FIELDS));
                    view.put("roleName", ClubRoles.roleName(club, member.getRole()));
                    return view;
                })
                .toList();
    }
}
