package com.campusconnect.feature.event;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.feature.venue.VenueService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * The campus-wide event planner (services/ScheduleService.js): every event holding time in a window, so clubs can
 * see what's already on before they pick a slot. Live and approved events hold their slot; events waiting for the
 * mentor are shown as tentative; drafts, returned, rejected and cancelled events don't hold time.
 */
@Service
public class EventPlanner {

    static final int MAX_DAYS = 31;
    static final List<EventStatus> CONFIRMED = List.of(EventStatus.PUBLISHED, EventStatus.APPROVED, EventStatus.COMPLETED);
    static final List<EventStatus> TENTATIVE = List.of(EventStatus.PENDING_APPROVAL);

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final PausedClubs pausedClubs;
    private final Refs refs;
    private final AppProperties properties;

    public EventPlanner(MongoOperations mongo, ClubAccess access, PausedClubs pausedClubs, Refs refs, AppProperties properties) {
        this.mongo = mongo;
        this.access = access;
        this.pausedClubs = pausedClubs;
        this.refs = refs;
        this.properties = properties;
    }

    /** The admin, faculty and club officers who plan events can see the whole campus schedule; returns the officer's clubs. */
    public List<String> assertCanPlan(AuthUser actor) {
        if (ClubAccess.isAdmin(actor) || ClubAccess.isFaculty(actor)) {
            return List.of();
        }
        List<String> clubs = access.clubIdsWithAnyPermission(actor.id(), List.of(ClubPermission.MANAGE_EVENTS, ClubPermission.PUBLISH_EVENTS));
        if (clubs.isEmpty()) {
            throw AppException.forbidden("The event planner is for club officers, mentors and the admin", ErrorCode.FORBIDDEN);
        }
        return clubs;
    }

    /**
     * Every event holding time between {@code from} (a university-calendar date, default today) and the following
     * {@code days} days. Times are UTC; the page lays them out in the university's timezone ({@code timezoneOffset}).
     */
    public Map<String, Object> getSchedule(AuthUser actor, String fromParam, String daysParam) {
        Set<String> mine = new HashSet<>(assertCanPlan(actor));
        String fromKey = fromParam != null && !fromParam.isEmpty() ? EventRules.dateKey(fromParam) : CampusTime.toDateKey(Instant.now().plusSeconds(CampusTime.zone().getTotalSeconds()));
        int days = Math.min(Math.max(parseDays(daysParam), 1), MAX_DAYS);
        Instant from = CampusTime.dateKeyToInstant(fromKey).minusSeconds(CampusTime.zone().getTotalSeconds());
        Instant to = from.plus(Duration.ofDays(days));

        Criteria filter = Criteria.where("status").in(List.of(CONFIRMED, TENTATIVE).stream().flatMap(List::stream).map(Enum::name).toList())
                .and("startAt").lt(to).and("endAt").gt(from);
        Set<String> paused = pausedClubs.ids();
        if (!paused.isEmpty()) {
            filter = filter.and("club").nin(paused.stream().map(ObjectId::new).toList());
        }
        Query query = Query.query(filter).with(Sort.by("startAt"));
        query.fields().include("title", "category", "status", "startAt", "endAt", "club", "venue", "eligibility", "registeredCount", "maxParticipants", "revision.status");
        List<Event> events = mongo.find(query, Event.class);

        Query clubQuery = Query.query(Criteria.where("_id").in(events.stream().map(Event::getClub).distinct().map(ObjectId::new).toList()));
        clubQuery.fields().include("name", "logo", "allDepartments", "departmentCodes");
        Map<String, Club> clubs = mongo.find(clubQuery, Club.class).stream().collect(Collectors.toMap(Club::getId, Function.identity()));
        Map<String, Map<String, Object>> venues = refs.load("venues", events.stream().map(Event::getVenue).toList(), "name", "location");

        List<Map<String, Object>> items = events.stream().filter(event -> clubs.containsKey(event.getClub())).map(event -> {
            Club club = clubs.get(event.getClub());
            Map<String, Object> clubView = new LinkedHashMap<>();
            clubView.put("_id", club.getId());
            clubView.put("name", club.getName());
            clubView.put("logo", club.getLogo());
            List<String> departments = event.getEligibility() == null ? List.of() : event.getEligibility().getDepartments();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("_id", event.getId());
            item.put("title", event.getTitle());
            item.put("category", event.getCategory());
            item.put("status", event.getStatus());
            item.put("tentative", TENTATIVE.contains(event.getStatus()));
            item.put("startAt", event.getStartAt());
            item.put("endAt", event.getEndAt());
            item.put("club", clubView);
            item.put("venue", venues.get(event.getVenue()));
            item.put("audience", VenueService.audienceOf(club, departments));
            item.put("batches", event.getEligibility() == null ? List.of() : event.getEligibility().getBatches());
            item.put("registeredCount", event.getRegisteredCount());
            item.put("maxParticipants", event.getMaxParticipants());
            item.put("mine", mine.contains(club.getId()));
            return item;
        }).toList();

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("from", from);
        schedule.put("to", to);
        schedule.put("fromDate", fromKey);
        schedule.put("days", days);
        schedule.put("timezoneOffset", properties.getUniversity().getTimezoneOffset());
        schedule.put("items", items);
        return schedule;
    }

    /** parseInt(days) || 7. */
    private static int parseDays(String value) {
        if (value == null) {
            return 7;
        }
        String trimmed = value.strip();
        int end = 0;
        if (end < trimmed.length() && (trimmed.charAt(0) == '-' || trimmed.charAt(0) == '+')) {
            end++;
        }
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        try {
            int parsed = Integer.parseInt(trimmed.substring(0, end));
            return parsed == 0 ? 7 : parsed;
        } catch (NumberFormatException e) {
            return 7;
        }
    }
}
